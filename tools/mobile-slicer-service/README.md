# OrcaSlicer Mobile Slicer Service

Small HTTP service for the planned Android/iOS companion app. The service runs on
a desktop machine, VM, or Proxmox host and delegates slicing to an OrcaSlicer CLI
binary.

This is intentionally separate from the desktop UI. Mobile clients upload a model
or project, receive a job id, poll job status, and download the generated output.

## Install

```bash
cd tools/mobile-slicer-service
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
```

## Run

Set the OrcaSlicer CLI path first. On a Proxmox Linux host this should point to a
Linux build of `orca-slicer`.

```bash
export ORCA_SLICER_BIN=/opt/orcaslicer/bin/orca-slicer
uvicorn app.main:app --host 0.0.0.0 --port 8787
```

Windows development example:

```powershell
$env:ORCA_SLICER_BIN="C:\path\to\orca-slicer.exe"
python -m uvicorn app.main:app --host 127.0.0.1 --port 8787
```

## API

- `GET /health`
- `POST /api/v1/jobs` with multipart field `file`
- `GET /api/v1/jobs/{job_id}`
- `GET /api/v1/jobs/{job_id}/output`
- `POST /api/v1/jobs/{job_id}/prepare-print`
- `POST /api/v1/jobs/{job_id}/upload-print`
- `POST /api/v1/jobs/{job_id}/start-print`
- `GET /api/v1/printer/status`
- `POST /api/v1/printer/cancel`

Optional multipart field `cli_args` may contain a JSON array of extra OrcaSlicer
CLI arguments. The service appends `--outputdir <job-output-dir> <uploaded-file>`.

Default CLI arguments are:

```json
["--slice", "0"]
```

Override them with:

```bash
export ORCA_SERVICE_DEFAULT_ARGS='--slice 0 --load-settings "/path/machine.json;/path/process.json" --load-filaments /path/filament.json'
```

## Bambu LAN printing

Direct Bambu printing is intentionally split into explicit steps. The service
does not start a printer immediately after slicing.

Set these variables only on the machine running the service:

```bash
export BAMBU_PRINTER_IP=192.168.1.26
export BAMBU_PRINTER_SERIAL=YOUR_PRINTER_SERIAL
export BAMBU_ACCESS_CODE=********
```

Workflow:

1. `POST /api/v1/jobs` uploads and slices the model.
2. `POST /api/v1/jobs/{job_id}/prepare-print` creates a Bambu-compatible
   `.gcode.3mf` containing `Metadata/plate_1.gcode`.
3. `POST /api/v1/jobs/{job_id}/upload-print` uploads that file to the printer
   over LAN FTPS.
4. `POST /api/v1/jobs/{job_id}/start-print` starts it with the Bambu
   `project_file` MQTT command.
5. `POST /api/v1/printer/cancel` sends the Orca-compatible `print.stop`
   command.

## Notes

This is a first server-side integration layer, not a hardened public internet
service. Put it behind a VPN or private network while developing. Authentication,
streaming uploads, persistent job storage, and preset discovery are planned
follow-up work.
