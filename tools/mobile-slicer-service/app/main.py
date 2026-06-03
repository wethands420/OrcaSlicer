from __future__ import annotations

import json
import os
import queue
import re
import shutil
import subprocess
import threading
import time
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse


JobState = Literal["queued", "running", "succeeded", "failed"]


def _data_dir() -> Path:
    return Path(os.environ.get("ORCA_SERVICE_DATA_DIR", "data")).resolve()


def _orcaslicer_bin() -> str:
    value = os.environ.get("ORCA_SLICER_BIN")
    if not value:
        raise RuntimeError("ORCA_SLICER_BIN is not set")
    return value


def _default_args() -> list[str]:
    raw = os.environ.get("ORCA_SERVICE_DEFAULT_ARGS")
    if not raw:
        return ["--export-gcode"]
    parsed = json.loads(raw)
    if not isinstance(parsed, list) or not all(isinstance(item, str) for item in parsed):
        raise ValueError("ORCA_SERVICE_DEFAULT_ARGS must be a JSON array of strings")
    return parsed


def _safe_name(name: str) -> str:
    name = Path(name).name.strip()
    name = re.sub(r"[^A-Za-z0-9._ -]", "_", name)
    return name or "upload.model"


@dataclass
class SliceJob:
    id: str
    filename: str
    input_path: str
    output_dir: str
    status: JobState = "queued"
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    command: list[str] = field(default_factory=list)
    exit_code: int | None = None
    error: str | None = None


app = FastAPI(title="OrcaSlicer Mobile Slicer Service", version="0.1.0")
jobs: dict[str, SliceJob] = {}
job_queue: queue.Queue[str] = queue.Queue()


def _job_dir(job_id: str) -> Path:
    return _data_dir() / "jobs" / job_id


def _set_status(job: SliceJob, status: JobState, error: str | None = None) -> None:
    job.status = status
    job.error = error
    job.updated_at = time.time()


def _parse_cli_args(raw: str | None) -> list[str]:
    if not raw:
        return []
    parsed = json.loads(raw)
    if not isinstance(parsed, list) or not all(isinstance(item, str) for item in parsed):
        raise HTTPException(status_code=400, detail="cli_args must be a JSON array of strings")
    return parsed


def _find_output_file(output_dir: Path) -> Path | None:
    preferred_suffixes = [".gcode", ".3mf", ".stl", ".obj"]
    files = [path for path in output_dir.rglob("*") if path.is_file()]
    for suffix in preferred_suffixes:
        for path in files:
            if path.name.lower().endswith(suffix):
                return path
    return files[0] if files else None


def _run_job(job_id: str) -> None:
    job = jobs[job_id]
    output_dir = Path(job.output_dir)
    stdout_path = _job_dir(job_id) / "stdout.log"
    stderr_path = _job_dir(job_id) / "stderr.log"

    try:
        _set_status(job, "running")
        command = [_orcaslicer_bin(), *_default_args(), *job.command, "--outputdir", str(output_dir), job.input_path]
        job.command = command
        job.updated_at = time.time()

        with stdout_path.open("wb") as stdout_file, stderr_path.open("wb") as stderr_file:
            completed = subprocess.run(command, stdout=stdout_file, stderr=stderr_file, check=False)

        job.exit_code = completed.returncode
        if completed.returncode == 0:
            _set_status(job, "succeeded")
        else:
            stderr_tail = stderr_path.read_text(errors="replace")[-4000:] if stderr_path.exists() else ""
            _set_status(job, "failed", stderr_tail or f"OrcaSlicer exited with code {completed.returncode}")
    except Exception as exc:
        _set_status(job, "failed", str(exc))


def _worker() -> None:
    while True:
        job_id = job_queue.get()
        try:
            _run_job(job_id)
        finally:
            job_queue.task_done()


@app.on_event("startup")
def _startup() -> None:
    (_data_dir() / "jobs").mkdir(parents=True, exist_ok=True)
    thread = threading.Thread(target=_worker, name="orcaslicer-job-worker", daemon=True)
    thread.start()


@app.get("/health")
def health() -> dict[str, object]:
    configured = bool(os.environ.get("ORCA_SLICER_BIN"))
    return {"ok": True, "orcaslicer_configured": configured}


@app.post("/api/v1/jobs")
async def create_job(file: UploadFile = File(...), cli_args: str | None = Form(default=None)) -> dict[str, object]:
    extra_args = _parse_cli_args(cli_args)
    job_id = str(uuid.uuid4())
    base_dir = _job_dir(job_id)
    input_dir = base_dir / "input"
    output_dir = base_dir / "output"
    input_dir.mkdir(parents=True, exist_ok=True)
    output_dir.mkdir(parents=True, exist_ok=True)

    filename = _safe_name(file.filename or "upload.model")
    input_path = input_dir / filename
    with input_path.open("wb") as target:
        shutil.copyfileobj(file.file, target)

    job = SliceJob(
        id=job_id,
        filename=filename,
        input_path=str(input_path),
        output_dir=str(output_dir),
        command=extra_args,
    )
    jobs[job_id] = job
    job_queue.put(job_id)
    return {"job": asdict(job)}


@app.get("/api/v1/jobs/{job_id}")
def get_job(job_id: str) -> dict[str, object]:
    job = jobs.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="job not found")

    output = _find_output_file(Path(job.output_dir))
    result = asdict(job)
    result["output_ready"] = output is not None and job.status == "succeeded"
    result["output_name"] = output.name if output else None
    return {"job": result}


@app.get("/api/v1/jobs/{job_id}/output")
def get_output(job_id: str) -> FileResponse:
    job = jobs.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="job not found")
    if job.status != "succeeded":
        raise HTTPException(status_code=409, detail=f"job is {job.status}")

    output = _find_output_file(Path(job.output_dir))
    if not output:
        raise HTTPException(status_code=404, detail="output file not found")
    return FileResponse(output, filename=output.name)
