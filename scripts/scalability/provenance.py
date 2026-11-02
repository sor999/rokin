import hashlib
import os
from pathlib import Path
import subprocess


def validate_output(root, output):
    """저장소 내부 결과는 Git에 추적되지 않는 무시 경로에만 저장한다."""
    root, output = Path(root).resolve(), Path(output).resolve()
    try:
        relative = output.relative_to(root)
    except ValueError:
        return output
    tracked = subprocess.run(['git', 'ls-files', '--error-unmatch', '--', str(relative)],
                             cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    ignored = subprocess.run(['git', 'check-ignore', '-q', '--', str(relative)], cwd=root)
    if tracked.returncode == 0 or ignored.returncode != 0:
        raise ValueError('저장소 내부 출력은 Git 무시 경로여야 합니다. tmp/scalability/를 사용하세요.')
    return output


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
