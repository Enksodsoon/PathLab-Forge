from __future__ import annotations

import json

from pathlab_adapt.optional_validation import run_optional_behavior_checks


def main() -> int:
    print(json.dumps(run_optional_behavior_checks(), indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
