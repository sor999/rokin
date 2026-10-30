import hashlib
import os
from pathlib import Path
import subprocess


def source_fingerprint(root):
    """Git 관리 파일과 무시되지 않은 새 파일의 경로·모드·내용을 해시한다."""
    names = subprocess.check_output(['git', 'ls-files', '-c', '-o', '--exclude-standard', '-z'], cwd=root)
    digest = hashlib.sha256()
    for name in sorted(set(names.split(b'\0')) - {b''}):
        path = Path(root) / os.fsdecode(name)
        if path.is_symlink():
            mode, content = b'link', os.fsencode(os.readlink(path))
        elif path.is_file():
            mode, content = str(path.stat().st_mode & 0o777).encode(), path.read_bytes()
        else:
            mode, content = b'deleted', b''
        for value in (name, mode, content):
            digest.update(len(value).to_bytes(8, 'big'))
            digest.update(value)
    return digest.hexdigest()
