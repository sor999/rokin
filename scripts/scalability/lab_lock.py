import contextlib
import fcntl
import os
from pathlib import Path
import subprocess
import sys

LOCK_PATH = Path(__file__).resolve().parents[2] / 'tmp/scalability/.lab.lock'


@contextlib.contextmanager
def lock(path=LOCK_PATH):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a') as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError('다른 측정 또는 환경 변경이 진행 중입니다. 종료 후 다시 실행하세요.') from None
        try:
            yield
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)


if __name__ == '__main__':
    try:
        with lock():
            result = subprocess.run(sys.argv[1:], env={**os.environ, 'ROKIN_SCALE_LOCK_HELD': '1'})
            raise SystemExit(result.returncode)
    except RuntimeError as error:
        raise SystemExit(str(error))
