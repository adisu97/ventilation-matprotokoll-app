import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_shell_scripts_parse():
    for rel in (
        "android/install.sh",
        "android/start.sh",
        "android/bootstrap.sh",
        "android/install_termux.sh",
    ):
        subprocess.run(["bash", "-n", str(ROOT / rel)], check=True)


def test_bootstrap_refuses_outside_termux():
    env = {"PATH": "/usr/bin:/bin", "HOME": "/tmp"}
    proc = subprocess.run(
        ["bash", str(ROOT / "android/bootstrap.sh")],
        env=env,
        capture_output=True,
        text=True,
    )
    assert proc.returncode != 0
    assert "Termux" in (proc.stdout + proc.stderr)


def test_install_refuses_outside_termux():
    env = {"PATH": "/usr/bin:/bin", "HOME": "/tmp"}
    proc = subprocess.run(
        ["bash", str(ROOT / "android/install.sh")],
        env=env,
        capture_output=True,
        text=True,
    )
    assert proc.returncode != 0
    assert "Termux" in (proc.stdout + proc.stderr)
