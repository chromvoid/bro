"""Run the SDK's PostgreSQL tier against an explicitly disposable database; skips are failures."""

import argparse
import json
import os
import re
import sys
import unittest
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]


def validate(path):
    config = json.loads(Path(path).read_text())
    names = {urlsplit(config[key].replace("postgresql+psycopg", "postgresql")).path.lstrip("/") for key in ("dsn", "migrator_dsn")}
    if len(names) != 1 or not re.fullmatch(r"device_assistant_test_[a-z0-9_]+", names.pop()):
        raise SystemExit("the PostgreSQL tier requires one device_assistant_test_* database")
    if config.get("disposable") is not True:
        raise SystemExit("test config must declare disposable=true")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--start", default=str(ROOT / "tests/integration"))
    args = parser.parse_args()
    validate(args.config)
    os.environ["SDK_TEST_DATABASE_CONFIG_FILE"] = str(args.config.resolve())
    suite = unittest.defaultTestLoader.discover(args.start, top_level_dir=args.start)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if result.skipped or result.testsRun == 0:
        sys.exit("the PostgreSQL tier may not be skipped")
    sys.exit(not result.wasSuccessful())
