# Incoming exports (staging, not data)

`calib_eval_data.py export` writes each phone export here as `export-<UTC>.tar` (with its `.sha256`) and extracts it beside the archive for verification. Sessions are then imported into `../raw/` (official) or `../pilot/` (PILOT*). Everything in this folder except this README is ignored by Git; delete exports only after a successful import and an off-repository backup.
