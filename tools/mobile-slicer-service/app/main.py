from __future__ import annotations

import json
import os
import queue
import re
import shlex
import shutil
import socket
import ssl
import struct
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from dataclasses import asdict, dataclass, field
from ftplib import FTP_TLS
from pathlib import Path
from typing import Any, Literal

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel


JobState = Literal["queued", "running", "succeeded", "failed"]
ImportType = Literal["geometry", "project_or_geometry", "archive"]
SUPPORTED_GEOMETRY_SUFFIXES = {".stl", ".obj", ".step", ".stp", ".svg"}
SUPPORTED_PROJECT_SUFFIXES = {".3mf", ".amf"}
SUPPORTED_ARCHIVE_SUFFIXES = {".zip"}


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
        return ["--slice", "0"]
    try:
        parsed = json.loads(raw)
        if isinstance(parsed, list) and all(isinstance(item, str) for item in parsed):
            return parsed
    except json.JSONDecodeError:
        pass

    stripped = raw.strip()
    if stripped.startswith("[") and stripped.endswith("]"):
        stripped = stripped[1:-1]
    parsed = shlex.split(stripped.replace(",", " "))
    if not parsed:
        raise ValueError("ORCA_SERVICE_DEFAULT_ARGS must not be empty")
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
    cli_args: list[str] = field(default_factory=list)
    command: list[str] = field(default_factory=list)
    exit_code: int | None = None
    error: str | None = None


@dataclass
class PreparedPrint:
    job_id: str
    path: str
    filename: str
    plate_gcode_path: str = "Metadata/plate_1.gcode"
    uploaded_filename: str | None = None
    uploaded_at: float | None = None


@dataclass
class RemoteDownloadJob:
    id: str
    url: str
    filename: str
    output_dir: str
    status: JobState = "queued"
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    path: str | None = None
    size: int | None = None
    mime_type: str | None = None
    error: str | None = None


@dataclass
class ImportedModel:
    id: str
    filename: str
    path: str
    import_type: str
    source_download_id: str
    source_entry_path: str | None = None
    created_at: float = field(default_factory=time.time)


class RemoteDownloadRequest(BaseModel):
    url: str
    filename: str | None = None
    mime_type: str | None = None
    user_agent: str | None = None
    cookie_header: str | None = None
    referer: str | None = None
    extra_headers: dict[str, str] = {}
    debug_log_cookies: bool = False


class ImportRequest(BaseModel):
    download_id: str
    entry_path: str | None = None


class PrinterStartRequest(BaseModel):
    prepared_filename: str | None = None
    timelapse: bool = False
    bed_levelling: bool = True
    flow_cali: bool = False
    vibration_cali: bool = False
    layer_inspect: bool = True
    use_ams: bool = False
    ams_mapping: str = ""


class ImplicitFTP_TLS(FTP_TLS):
    def connect(self, host: str = "", port: int = 0, timeout: float | object = -999, source_address=None):
        if host:
            self.host = host
        if port:
            self.port = port
        if timeout != -999:
            self.timeout = timeout
        self.sock = socket.create_connection((self.host, self.port), self.timeout, source_address)
        self.af = self.sock.family
        self.sock = self.context.wrap_socket(self.sock, server_hostname=self.host)
        self.file = self.sock.makefile("r", encoding=self.encoding)
        self.welcome = self.getresp()
        return self.welcome


app = FastAPI(title="OrcaSlicer Mobile Slicer Service", version="0.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["*"],
)
jobs: dict[str, SliceJob] = {}
prepared_prints: dict[str, PreparedPrint] = {}
remote_downloads: dict[str, RemoteDownloadJob] = {}
imported_models: dict[str, ImportedModel] = {}
job_queue: queue.Queue[str] = queue.Queue()


def _job_dir(job_id: str) -> Path:
    return _data_dir() / "jobs" / job_id


def _prepared_print_path(job_id: str) -> Path:
    return _job_dir(job_id) / "print" / f"{job_id}.gcode.3mf"


def _remote_download_dir(download_id: str) -> Path:
    return _data_dir() / "remote-downloads" / download_id


def _imports_dir() -> Path:
    return _data_dir() / "imports"


def _set_status(job: SliceJob, status: JobState, error: str | None = None) -> None:
    job.status = status
    job.error = error
    job.updated_at = time.time()


def _parse_cli_args(raw: str | None) -> list[str]:
    if not raw:
        return []
    try:
        parsed = json.loads(raw)
        if isinstance(parsed, list) and all(isinstance(item, str) for item in parsed):
            return parsed
    except json.JSONDecodeError:
        pass

    parsed = shlex.split(raw)
    if parsed:
        return parsed
    raise HTTPException(status_code=400, detail="cli_args must contain at least one argument")


def _find_output_file(output_dir: Path) -> Path | None:
    preferred_suffixes = [".gcode", ".3mf", ".stl", ".obj"]
    files = [path for path in output_dir.rglob("*") if path.is_file()]
    for suffix in preferred_suffixes:
        for path in files:
            if path.name.lower().endswith(suffix):
                return path
    return files[0] if files else None


def _detect_import_type(path: Path) -> ImportType | None:
    suffix = path.suffix.lower()
    if suffix in SUPPORTED_GEOMETRY_SUFFIXES:
        return "geometry"
    if suffix in SUPPORTED_PROJECT_SUFFIXES:
        return "project_or_geometry"
    if suffix in SUPPORTED_ARCHIVE_SUFFIXES:
        return "archive"
    return None


def _binary_stl_name(path: Path) -> str:
    return _safe_name(path.with_suffix(".stl").name)


def _looks_like_binary_stl(path: Path) -> bool:
    try:
        size = path.stat().st_size
        if size < 84:
            return False
        with path.open("rb") as file:
            header = file.read(84)
        triangle_count = struct.unpack("<I", header[80:84])[0]
    except OSError:
        return False

    return 84 + triangle_count * 50 == size


def _is_safe_zip_member(name: str) -> bool:
    entry = Path(name)
    return bool(name) and not entry.is_absolute() and ".." not in entry.parts


def _looks_like_3mf_archive(path: Path) -> bool:
    if not zipfile.is_zipfile(path):
        return False
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
    return "[Content_Types].xml" in names and "3D/3dmodel.model" in names


def _inspect_download_file(path: Path) -> dict[str, Any]:
    import_type = _detect_import_type(path)
    if not import_type:
        if zipfile.is_zipfile(path):
            import_type = "archive"
        elif _looks_like_binary_stl(path):
            return {
                "kind": "geometry",
                "filename": _binary_stl_name(path),
                "size": path.stat().st_size,
                "supported_entries": [
                    {
                        "path": path.name,
                        "filename": _binary_stl_name(path),
                        "import_type": "geometry",
                        "size": path.stat().st_size,
                    }
                ],
            }
        else:
            return {
                "kind": "unsupported",
                "filename": path.name,
                "size": path.stat().st_size,
                "supported_entries": [],
            }

    if import_type == "archive" and path.suffix.lower() not in SUPPORTED_ARCHIVE_SUFFIXES and _looks_like_3mf_archive(path):
        return {
            "kind": "project_or_geometry",
            "filename": path.name,
            "size": path.stat().st_size,
            "supported_entries": [
                {"path": path.name, "filename": path.name, "import_type": "project_or_geometry", "size": path.stat().st_size}
            ],
        }

    if not import_type:
        return {
            "kind": "unsupported",
            "filename": path.name,
            "size": path.stat().st_size,
            "supported_entries": [],
        }

    if import_type != "archive":
        return {
            "kind": import_type,
            "filename": path.name,
            "size": path.stat().st_size,
            "supported_entries": [
                {"path": path.name, "filename": path.name, "import_type": import_type, "size": path.stat().st_size}
            ],
        }

    supported_entries: list[dict[str, Any]] = []
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            if not _is_safe_zip_member(info.filename):
                raise ValueError(f"unsafe zip entry: {info.filename}")
            entry_type = _detect_import_type(Path(info.filename))
            if entry_type and entry_type != "archive":
                supported_entries.append(
                    {
                        "path": info.filename,
                        "filename": Path(info.filename).name,
                        "import_type": entry_type,
                        "size": info.file_size,
                    }
                )

    return {
        "kind": "archive",
        "filename": path.name,
        "size": path.stat().st_size,
        "supported_entries": supported_entries,
    }


def _max_remote_download_bytes() -> int:
    return int(os.environ.get("ORCA_SERVICE_MAX_REMOTE_DOWNLOAD_BYTES", str(512 * 1024 * 1024)))


def _download_remote_file(download_id: str, request: RemoteDownloadRequest) -> None:
    job = remote_downloads[download_id]
    target_dir = Path(job.output_dir)
    tmp_path = target_dir / f"{job.filename}.download"
    final_path = target_dir / job.filename
    headers = dict(request.extra_headers)
    if request.user_agent:
        headers["User-Agent"] = request.user_agent
    if request.cookie_header:
        headers["Cookie"] = request.cookie_header
    if request.referer:
        headers["Referer"] = request.referer

    try:
        _set_download_status(job, "running")
        if request.debug_log_cookies and request.cookie_header:
            (target_dir / "cookies.log").write_text(request.cookie_header, encoding="utf-8")

        req = urllib.request.Request(request.url, headers=headers)
        max_bytes = _max_remote_download_bytes()
        written = 0
        with urllib.request.urlopen(req, timeout=60) as response, tmp_path.open("wb") as target:
            job.mime_type = request.mime_type or response.headers.get_content_type()
            (target_dir / "metadata.json").write_text(
                json.dumps(
                    {
                        "request": {
                            "url": request.url,
                            "filename": request.filename,
                            "mime_type": request.mime_type,
                            "referer": request.referer,
                            "has_cookie_header": bool(request.cookie_header),
                            "extra_header_names": sorted(request.extra_headers.keys()),
                        },
                        "response": {
                            "url": response.geturl(),
                            "status": response.status,
                            "content_type": response.headers.get_content_type(),
                            "content_length": response.headers.get("Content-Length"),
                            "content_disposition": response.headers.get("Content-Disposition"),
                        },
                    },
                    indent=2,
                ),
                encoding="utf-8",
            )
            while True:
                chunk = response.read(1024 * 256)
                if not chunk:
                    break
                written += len(chunk)
                if written > max_bytes:
                    raise ValueError(f"download exceeds limit of {max_bytes} bytes")
                target.write(chunk)

        tmp_path.replace(final_path)
        job.path = str(final_path)
        job.size = final_path.stat().st_size
        _set_download_status(job, "succeeded")
    except Exception as exc:
        if tmp_path.exists():
            tmp_path.unlink()
        _set_download_status(job, "failed", str(exc))


def _set_download_status(job: RemoteDownloadJob, status: JobState, error: str | None = None) -> None:
    job.status = status
    job.error = error
    job.updated_at = time.time()


def _printer_ip() -> str:
    value = os.environ.get("BAMBU_PRINTER_IP")
    if not value:
        raise HTTPException(status_code=503, detail="BAMBU_PRINTER_IP is not set")
    return value


def _printer_serial() -> str:
    value = os.environ.get("BAMBU_PRINTER_SERIAL")
    if not value:
        raise HTTPException(status_code=503, detail="BAMBU_PRINTER_SERIAL is not set")
    return value


def _printer_access_code() -> str:
    value = os.environ.get("BAMBU_ACCESS_CODE")
    if not value:
        raise HTTPException(status_code=503, detail="BAMBU_ACCESS_CODE is not set")
    return value


def _mqtt_encode_string(value: str) -> bytes:
    data = value.encode("utf-8")
    return struct.pack("!H", len(data)) + data


def _mqtt_remaining_length(length: int) -> bytes:
    encoded: list[int] = []
    while True:
        byte = length % 128
        length //= 128
        if length:
            byte |= 128
        encoded.append(byte)
        if not length:
            break
    return bytes(encoded)


def _mqtt_packet(packet_type: int, payload: bytes) -> bytes:
    return bytes([packet_type]) + _mqtt_remaining_length(len(payload)) + payload


def _mqtt_read_packet(sock: ssl.SSLSocket, timeout: float = 5.0) -> tuple[int | None, bytes]:
    sock.settimeout(timeout)
    first = sock.recv(1)
    if not first:
        return None, b""

    multiplier = 1
    length = 0
    while True:
        byte = sock.recv(1)[0]
        length += (byte & 127) * multiplier
        if not byte & 128:
            break
        multiplier *= 128

    payload = b""
    while len(payload) < length:
        payload += sock.recv(length - len(payload))
    return first[0], payload


def _mqtt_connect(client_id: str) -> ssl.SSLSocket:
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    raw_sock = socket.create_connection((_printer_ip(), 8883), timeout=10)
    sock = context.wrap_socket(raw_sock, server_hostname=_printer_ip())
    payload = (
        _mqtt_encode_string("MQTT")
        + bytes([4, 0xC2])
        + struct.pack("!H", 60)
        + _mqtt_encode_string(client_id)
        + _mqtt_encode_string("bblp")
        + _mqtt_encode_string(_printer_access_code())
    )
    sock.sendall(_mqtt_packet(0x10, payload))
    packet_type, data = _mqtt_read_packet(sock, timeout=8)
    if packet_type != 0x20 or len(data) < 2 or data[1] != 0:
        sock.close()
        raise HTTPException(status_code=502, detail="printer MQTT login failed")
    return sock


def _mqtt_subscribe(sock: ssl.SSLSocket, topic: str) -> None:
    payload = struct.pack("!H", 1) + _mqtt_encode_string(topic) + bytes([0])
    sock.sendall(_mqtt_packet(0x82, payload))
    _mqtt_read_packet(sock, timeout=5)


def _mqtt_publish(sock: ssl.SSLSocket, topic: str, body: dict[str, object], qos: int = 0) -> None:
    payload = _mqtt_encode_string(topic)
    if qos == 1:
        payload += struct.pack("!H", 2)
    payload += json.dumps(body, separators=(",", ":")).encode("utf-8")
    sock.sendall(_mqtt_packet(0x30 | (qos << 1), payload))


def _mqtt_collect_reports(sock: ssl.SSLSocket, seconds: float) -> list[dict[str, object]]:
    reports: list[dict[str, object]] = []
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            packet_type, payload = _mqtt_read_packet(sock, timeout=2)
        except socket.timeout:
            continue
        if packet_type == 0x40:
            continue
        if not packet_type or packet_type >> 4 != 3 or len(payload) < 2:
            continue

        topic_len = struct.unpack("!H", payload[:2])[0]
        body_start = 2 + topic_len
        if packet_type & 0x06:
            body_start += 2
        try:
            reports.append(json.loads(payload[body_start:].decode("utf-8", errors="replace")))
        except json.JSONDecodeError:
            continue
    return reports


def _printer_command(body: dict[str, object], qos: int = 0, wait_seconds: float = 8.0) -> list[dict[str, object]]:
    serial = _printer_serial()
    sock = _mqtt_connect(f"orcaslicer-mobile-{uuid.uuid4()}")
    try:
        _mqtt_subscribe(sock, f"device/{serial}/report")
        _mqtt_publish(sock, f"device/{serial}/request", body, qos=qos)
        return _mqtt_collect_reports(sock, wait_seconds)
    finally:
        sock.close()


def _ftp_upload(local_path: Path, remote_name: str) -> None:
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    ftp = ImplicitFTP_TLS(context=context, timeout=45)
    try:
        ftp.connect(_printer_ip(), 990)
        ftp.login("bblp", _printer_access_code())
        ftp.prot_p()
        with local_path.open("rb") as source:
            try:
                ftp.storbinary(f"STOR {remote_name}", source, blocksize=1024 * 256)
            except TimeoutError:
                # Bambu FTPS may time out during TLS unwrap after a completed transfer.
                pass
    finally:
        try:
            ftp.close()
        except Exception:
            pass


def _ftp_file_exists(remote_name: str) -> bool:
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    ftp = ImplicitFTP_TLS(context=context, timeout=20)
    try:
        ftp.connect(_printer_ip(), 990)
        ftp.login("bblp", _printer_access_code())
        ftp.prot_p()
        items: list[str] = []
        ftp.retrlines("LIST", items.append)
        return any(remote_name in item for item in items)
    finally:
        try:
            ftp.quit()
        except Exception:
            ftp.close()


def _summarize_reports(reports: list[dict[str, object]]) -> dict[str, object]:
    summary: dict[str, object] = {}
    for report in reports:
        print_report = report.get("print")
        pushing_report = report.get("pushing")
        if isinstance(print_report, dict):
            for key in (
                "sequence_id",
                "command",
                "result",
                "reason",
                "gcode_state",
                "print_type",
                "mc_print_stage",
                "mc_percent",
                "gcode_file",
                "subtask_name",
                "print_error",
                "nozzle_target_temper",
                "bed_target_temper",
                "nozzle_temper",
                "bed_temper",
                "mc_remaining_time",
            ):
                if key in print_report:
                    summary[key] = print_report[key]
        if isinstance(pushing_report, dict):
            summary["pushing"] = pushing_report
    return summary


def _run_job(job_id: str) -> None:
    job = jobs[job_id]
    output_dir = Path(job.output_dir)
    stdout_path = _job_dir(job_id) / "stdout.log"
    stderr_path = _job_dir(job_id) / "stderr.log"

    try:
        _set_status(job, "running")
        command = [_orcaslicer_bin(), *_default_args(), *job.cli_args, "--outputdir", str(output_dir), job.input_path]
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
    (_data_dir() / "remote-downloads").mkdir(parents=True, exist_ok=True)
    _imports_dir().mkdir(parents=True, exist_ok=True)
    thread = threading.Thread(target=_worker, name="orcaslicer-job-worker", daemon=True)
    thread.start()


@app.get("/health")
def health() -> dict[str, object]:
    configured = bool(os.environ.get("ORCA_SLICER_BIN"))
    printer_configured = all(os.environ.get(key) for key in ("BAMBU_PRINTER_IP", "BAMBU_PRINTER_SERIAL", "BAMBU_ACCESS_CODE"))
    return {"ok": True, "orcaslicer_configured": configured, "printer_configured": printer_configured}


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
        cli_args=extra_args,
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


@app.post("/api/v1/remote-downloads")
def create_remote_download(request: RemoteDownloadRequest) -> dict[str, object]:
    download_id = str(uuid.uuid4())
    output_dir = _remote_download_dir(download_id)
    output_dir.mkdir(parents=True, exist_ok=True)
    filename = _safe_name(request.filename or Path(urllib.parse.urlparse(request.url).path).name or "download.model")
    job = RemoteDownloadJob(
        id=download_id,
        url=request.url,
        filename=filename,
        output_dir=str(output_dir),
        mime_type=request.mime_type,
    )
    remote_downloads[download_id] = job
    thread = threading.Thread(
        target=_download_remote_file,
        args=(download_id, request),
        name=f"remote-download-{download_id}",
        daemon=True,
    )
    thread.start()
    return {"download": asdict(job)}


@app.post("/api/v1/remote-download-uploads")
async def create_remote_download_upload(
    file: UploadFile = File(...),
    source_url: str = Form(default=""),
    mime_type: str | None = Form(default=None),
    referer: str | None = Form(default=None),
) -> dict[str, object]:
    download_id = str(uuid.uuid4())
    output_dir = _remote_download_dir(download_id)
    output_dir.mkdir(parents=True, exist_ok=True)
    filename = _safe_name(file.filename or "download.model")
    final_path = output_dir / filename
    with final_path.open("wb") as target:
        shutil.copyfileobj(file.file, target)

    job = RemoteDownloadJob(
        id=download_id,
        url=source_url,
        filename=filename,
        output_dir=str(output_dir),
        status="succeeded",
        path=str(final_path),
        size=final_path.stat().st_size,
        mime_type=mime_type or file.content_type,
    )
    remote_downloads[download_id] = job
    (output_dir / "metadata.json").write_text(
        json.dumps(
            {
                "request": {
                    "url": source_url,
                    "filename": filename,
                    "mime_type": mime_type,
                    "referer": referer,
                    "transport": "webview_blob_upload",
                },
                "response": {
                    "content_type": file.content_type,
                    "content_length": job.size,
                },
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    return {"download": asdict(job)}


@app.get("/api/v1/remote-downloads/{download_id}")
def get_remote_download(download_id: str) -> dict[str, object]:
    job = remote_downloads.get(download_id)
    if not job:
        raise HTTPException(status_code=404, detail="download not found")
    result = asdict(job)
    result["inspect_ready"] = job.status == "succeeded" and job.path is not None
    return {"download": result}


@app.post("/api/v1/remote-downloads/{download_id}/inspect")
def inspect_remote_download(download_id: str) -> dict[str, object]:
    job = remote_downloads.get(download_id)
    if not job:
        raise HTTPException(status_code=404, detail="download not found")
    if job.status != "succeeded" or not job.path:
        raise HTTPException(status_code=409, detail=f"download is {job.status}")
    try:
        inspection = _inspect_download_file(Path(job.path))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return {"download": asdict(job), "inspection": inspection}


@app.post("/api/v1/imports")
def create_import(request: ImportRequest) -> dict[str, object]:
    download = remote_downloads.get(request.download_id)
    if not download:
        raise HTTPException(status_code=404, detail="download not found")
    if download.status != "succeeded" or not download.path:
        raise HTTPException(status_code=409, detail=f"download is {download.status}")

    source_path = Path(download.path)
    model_id = str(uuid.uuid4())
    target_dir = _imports_dir() / model_id
    target_dir.mkdir(parents=True, exist_ok=True)
    source_import_type = _detect_import_type(source_path)
    if not source_import_type and zipfile.is_zipfile(source_path):
        source_import_type = "archive"

    if source_import_type == "archive" and not request.entry_path and _looks_like_3mf_archive(source_path):
        import_type = "project_or_geometry"
        target_path = target_dir / _safe_name(source_path.with_suffix(".3mf").name)
        shutil.copy2(source_path, target_path)
        source_entry_path = None
    elif source_import_type == "archive":
        if not request.entry_path:
            raise HTTPException(status_code=400, detail="archive import requires selecting a supported entry")
        with zipfile.ZipFile(source_path) as archive:
            if not _is_safe_zip_member(request.entry_path):
                raise HTTPException(status_code=400, detail="unsafe zip entry")
            try:
                info = archive.getinfo(request.entry_path)
            except KeyError as exc:
                raise HTTPException(status_code=400, detail="zip entry not found") from exc
            import_type = _detect_import_type(Path(info.filename))
            if not import_type or import_type == "archive":
                raise HTTPException(status_code=400, detail="unsupported zip entry")
            target_path = target_dir / _safe_name(Path(info.filename).name)
            with archive.open(info) as source, target_path.open("wb") as target:
                shutil.copyfileobj(source, target)
            source_entry_path = request.entry_path
    else:
        import_type = source_import_type
        if not import_type and _looks_like_binary_stl(source_path):
            import_type = "geometry"
            target_path = target_dir / _binary_stl_name(source_path)
        else:
            if not import_type or import_type == "archive":
                raise HTTPException(status_code=400, detail="download requires selecting a supported archive entry")
            target_path = target_dir / _safe_name(source_path.name)
        shutil.copy2(source_path, target_path)
        source_entry_path = None

    imported = ImportedModel(
        id=model_id,
        filename=target_path.name,
        path=str(target_path),
        import_type=import_type,
        source_download_id=request.download_id,
        source_entry_path=source_entry_path,
    )
    imported_models[model_id] = imported
    return {"imported_model": asdict(imported)}


@app.get("/api/v1/imports")
def list_imports() -> dict[str, object]:
    return {"imported_models": [asdict(model) for model in imported_models.values()]}


@app.post("/api/v1/jobs/{job_id}/prepare-print")
def prepare_print(job_id: str) -> dict[str, object]:
    job = jobs.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="job not found")
    if job.status != "succeeded":
        raise HTTPException(status_code=409, detail=f"job is {job.status}")

    target_path = _prepared_print_path(job_id)
    target_path.parent.mkdir(parents=True, exist_ok=True)
    stdout_path = target_path.parent / "prepare-print.stdout.log"
    stderr_path = target_path.parent / "prepare-print.stderr.log"
    command = [_orcaslicer_bin(), *_default_args(), *job.cli_args, "--export-3mf", str(target_path), job.input_path]

    with stdout_path.open("wb") as stdout_file, stderr_path.open("wb") as stderr_file:
        completed = subprocess.run(command, stdout=stdout_file, stderr=stderr_file, check=False)

    if completed.returncode != 0 or not target_path.exists():
        stderr_tail = stderr_path.read_text(errors="replace")[-4000:] if stderr_path.exists() else ""
        raise HTTPException(status_code=500, detail=stderr_tail or f"OrcaSlicer exited with code {completed.returncode}")

    prepared = PreparedPrint(job_id=job_id, path=str(target_path), filename=target_path.name)
    prepared_prints[job_id] = prepared
    return {"prepared_print": asdict(prepared)}


@app.post("/api/v1/jobs/{job_id}/upload-print")
def upload_print(job_id: str) -> dict[str, object]:
    prepared = prepared_prints.get(job_id)
    if not prepared:
        path = _prepared_print_path(job_id)
        if not path.exists():
            raise HTTPException(status_code=409, detail="print file is not prepared")
        prepared = PreparedPrint(job_id=job_id, path=str(path), filename=path.name)
        prepared_prints[job_id] = prepared

    local_path = Path(prepared.path)
    if not local_path.exists():
        raise HTTPException(status_code=404, detail="prepared print file not found")

    remote_name = _safe_name(prepared.filename)
    _ftp_upload(local_path, remote_name)
    if not _ftp_file_exists(remote_name):
        raise HTTPException(status_code=502, detail="printer upload could not be verified")

    prepared.uploaded_filename = remote_name
    prepared.uploaded_at = time.time()
    return {"prepared_print": asdict(prepared), "uploaded": True}


@app.get("/api/v1/printer/status")
def printer_status() -> dict[str, object]:
    reports = _printer_command(
        {"pushing": {"sequence_id": str(int(time.time())), "command": "pushall", "version": 1, "push_target": 1}},
        wait_seconds=8,
    )
    return {"status": _summarize_reports(reports), "reports": reports[-3:]}


@app.post("/api/v1/jobs/{job_id}/start-print")
def start_print(job_id: str, request: PrinterStartRequest | None = None) -> dict[str, object]:
    request = request or PrinterStartRequest()
    prepared = prepared_prints.get(job_id)
    if not prepared:
        raise HTTPException(status_code=409, detail="print file is not prepared and uploaded")

    remote_name = request.prepared_filename or prepared.uploaded_filename
    if not remote_name:
        raise HTTPException(status_code=409, detail="print file has not been uploaded")
    remote_name = _safe_name(remote_name)

    sequence_id = str(int(time.time()))
    body = {
        "print": {
            "sequence_id": sequence_id,
            "command": "project_file",
            "param": prepared.plate_gcode_path,
            "project_id": "0",
            "profile_id": "0",
            "task_id": "0",
            "subtask_id": "0",
            "subtask_name": Path(remote_name).stem[:64],
            "file": "",
            "url": f"ftp:///{remote_name}",
            "md5": "",
            "timelapse": request.timelapse,
            "bed_type": "auto",
            "bed_levelling": request.bed_levelling,
            "flow_cali": request.flow_cali,
            "vibration_cali": request.vibration_cali,
            "layer_inspect": request.layer_inspect,
            "ams_mapping": request.ams_mapping,
            "use_ams": request.use_ams,
        }
    }
    reports = _printer_command(body, wait_seconds=12)
    summary = _summarize_reports(reports)
    if summary.get("result") == "fail":
        raise HTTPException(status_code=502, detail={"message": "printer rejected start command", "status": summary})
    return {"started": summary.get("result") == "success" or summary.get("gcode_state") in {"PREPARE", "RUNNING"}, "status": summary}


@app.post("/api/v1/printer/cancel")
def cancel_print() -> dict[str, object]:
    body = {"print": {"sequence_id": str(int(time.time())), "command": "stop", "param": ""}}
    reports = _printer_command(body, qos=1, wait_seconds=12)
    return {"cancel_requested": True, "status": _summarize_reports(reports)}
