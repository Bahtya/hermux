#!/usr/bin/env python3
"""
Build pre-built Python venv on a real Termux ARM64 device via SSH.

Prerequisites:
  - Windows/macOS/Linux host with Python 3.10+ and paramiko installed
  - ADB port forwarding: adb forward tcp:8022 tcp:8022
  - Termux device with sshd running and build dependencies installed:
    pkg install python rust make clang pkg-config libffi openssl ca-certificates git

Usage:
  pip install paramiko
  python scripts/build-venv-device.py --host 127.0.0.1 --port 8022 \
      --user u0_a401 --password <password> \
      --hermes-commit 486b692ddd801f8f665d3fff023149fb1cb6509e \
      --output venv-aarch64.tar.gz

The script will:
  1. SSH into the Termux device
  2. Clone/update hermes-agent at the specified commit
  3. Fix missing package headers (known Termux issue)
  4. Create venv, build psutil (with official Android patch)
  5. Install hermes-agent with all dependencies
  6. Pack venv as tar.gz and download via SFTP
"""

import argparse
import os
import sys

import paramiko


def safe_print(text):
    """Print text safely on Windows (handle GBK encoding)."""
    try:
        print(text)
    except UnicodeEncodeError:
        print(text.encode("ascii", errors="replace").decode("ascii"))


def ssh_exec(ssh, cmd, timeout=600):
    """Execute command via SSH, print output, return exit code."""
    print(f"\n>>> {cmd[:120]}{'...' if len(cmd) > 120 else ''}")
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    exit_code = stdout.channel.recv_exit_status()

    out = stdout.read().decode("utf-8", errors="replace").strip()
    err = stderr.read().decode("utf-8", errors="replace").strip()

    if out:
        # Print last 2000 chars to keep output manageable
        if len(out) > 2000:
            print(f"... (truncated, showing last 2000 chars)")
            safe_print(out[-2000:])
        else:
            safe_print(out)
    if err and "WARNING" not in err:
        safe_print(f"STDERR: {err[-500:]}")

    return exit_code, out, err


def main():
    parser = argparse.ArgumentParser(description="Build venv on Termux device via SSH")
    parser.add_argument("--host", default="127.0.0.1", help="SSH host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=8022, help="SSH port (default: 8022)")
    parser.add_argument("--user", required=True, help="SSH username")
    parser.add_argument("--password", required=True, help="SSH password")
    parser.add_argument(
        "--hermes-commit",
        default="486b692ddd801f8f665d3fff023149fb1cb6509e",
        help="Hermes-agent git commit hash",
    )
    parser.add_argument(
        "--output",
        default="venv-aarch64.tar.gz",
        help="Output file path (default: venv-aarch64.tar.gz)",
    )
    args = parser.parse_args()

    # Connect
    print(f"=== Connecting to {args.user}@{args.host}:{args.port} ===")
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(args.host, args.port, args.user, args.password, timeout=15)
    print("Connected!")

    try:
        # Environment setup
        env = " && ".join([
            "export PREFIX=/data/data/com.termux/files/usr",
            "export HOME=/data/data/com.termux/files/home",
            "export PATH=$PREFIX/bin:$PATH",
            "export TMPDIR=$PREFIX/tmp",
            "export LD_LIBRARY_PATH=$PREFIX/lib",
            f"export ANDROID_API_LEVEL=$(getprop ro.build.version.sdk 2>/dev/null || echo 24)",
        ])

        HERMES_DIR = "$HOME/.hermes/hermes-agent"
        VENV_DIR = f"{HERMES_DIR}/venv"

        # Expanded paths for subprocess calls (no shell expansion)
        EXPANDED_HOME = "/data/data/com.termux/files/home"
        EXPANDED_HERMES_DIR = f"{EXPANDED_HOME}/.hermes/hermes-agent"
        EXPANDED_VENV_DIR = f"{EXPANDED_HERMES_DIR}/venv"

        # Step 1: Fix missing package headers (known Termux issue)
        print("\n=== Step 1: Fix missing package headers ===")
        ssh_exec(ssh, f"{env} && apt install --reinstall -y python ndk-sysroot libffi 2>&1 | tail -5", timeout=120)

        # Step 2: Clone/update hermes-agent
        print("\n=== Step 2: Clone hermes-agent ===")
        ssh_exec(ssh, f"{env} && rm -rf {HERMES_DIR} && mkdir -p $(dirname {HERMES_DIR})")
        ssh_exec(ssh, f"{env} && git clone https://github.com/NousResearch/hermes-agent.git {HERMES_DIR} 2>&1 | tail -3", timeout=120)
        ssh_exec(ssh, f"{env} && cd {HERMES_DIR} && git checkout {args.hermes_commit} 2>&1")

        # Step 3: Create venv and upgrade pip
        print("\n=== Step 3: Create venv ===")
        ssh_exec(ssh, f"{env} && rm -rf {VENV_DIR} && python -m venv {VENV_DIR}")
        ssh_exec(ssh, f"{env} && {VENV_DIR}/bin/pip install --upgrade pip setuptools wheel 2>&1 | tail -3", timeout=120)

        # Step 4: Build psutil using official hermes-agent script
        print("\n=== Step 4: Build psutil (official Android patch) ===")
        exit_code, _, _ = ssh_exec(
            ssh,
            f"{env} && python {HERMES_DIR}/scripts/install_psutil_android.py --pip '{EXPANDED_VENV_DIR}/bin/pip' 2>&1",
            timeout=600,
        )
        if exit_code != 0:
            print("FATAL: psutil build failed")
            sys.exit(1)

        # Step 5: Install hermes-agent
        print("\n=== Step 5: Install hermes-agent [termux-all] ===")
        exit_code, _, _ = ssh_exec(
            ssh,
            f"{env} && cd {HERMES_DIR} && {VENV_DIR}/bin/pip install -c constraints-termux.txt '.[termux-all]' 2>&1",
            timeout=1800,
        )

        # jiter often fails in bulk install; install separately if needed
        ssh_exec(ssh, f"{env} && {VENV_DIR}/bin/pip install jiter 2>&1", timeout=300)

        # Retry hermes-agent if first attempt failed
        if exit_code != 0:
            print("Retrying hermes-agent install...")
            ssh_exec(
                ssh,
                f"{env} && cd {HERMES_DIR} && {VENV_DIR}/bin/pip install -c constraints-termux.txt '.[termux-all]' 2>&1",
                timeout=1800,
            )

        # Step 5b: Install feishu dependencies (not included in termux-all)
        print("\n=== Step 5b: Install feishu dependencies ===")
        ssh_exec(
            ssh,
            f"{env} && cd {HERMES_DIR} && {VENV_DIR}/bin/pip install -c constraints-termux.txt '.[feishu]' 2>&1",
            timeout=600,
        )

        # Step 5c: Install pycryptodome (needs CC=clang on Termux)
        print("\n=== Step 5c: Install pycryptodome ===")
        ssh_exec(ssh, f"{env} && CC=clang {VENV_DIR}/bin/pip install pycryptodome 2>&1", timeout=300)

        # Step 5d: Fix Android compatibility issues
        print("\n=== Step 5d: Fix Android compatibility ===")
        # watchfiles C extension crashes on Android ARM64 (SIGABRT)
        ssh_exec(ssh, f"{env} && {VENV_DIR}/bin/pip uninstall -y watchfiles 2>&1")
        # websockets version conflict: lark-oapi requires <16
        ssh_exec(ssh, f"{env} && {VENV_DIR}/bin/pip install 'websockets<16' 2>&1", timeout=120)

        # Step 6: Validate
        print("\n=== Step 6: Validate venv ===")
        exit_code, out, _ = ssh_exec(
            ssh,
            f"{env} && {VENV_DIR}/bin/python -c \""
            f"import hermes_cli; print('hermes_cli: OK'); "
            f"import psutil; print(f'psutil: {{psutil.__version__}}'); "
            f"import cryptography; print(f'cryptography: {{cryptography.__version__}}'); "
            f"import jiter; print(f'jiter: {{jiter.__version__}}'); "
            f"import openai; print(f'openai: {{openai.__version__}}'); "
            f"import lark_oapi; print('lark_oapi: OK'); "
            f"import Crypto; print('pycryptodome: OK'); "
            f"print('ALL IMPORTS OK!')\" 2>&1",
        )
        if "ALL IMPORTS OK" not in out:
            print("FATAL: venv validation failed")
            sys.exit(1)

        # Step 7: Write build metadata
        print("\n=== Step 7: Write build metadata ===")
        ssh_exec(
            ssh,
            f"{env} && {VENV_DIR}/bin/python -c \""
            f"import importlib.metadata; "
            f"ver = importlib.metadata.version('hermes-agent'); "
            f"open('{VENV_DIR}/.hermes-build-info', 'w').write("
            f"'HERMES_AGENT_COMMIT={args.hermes_commit}\\n'"
            f"+ 'HERMES_AGENT_VERSION=' + ver + '\\n'"
            f")\" 2>&1",
        )

        # Step 8: Fix com.termux → com.hermux paths before packing
        print("\n=== Step 8: Fix paths com.termux → com.hermux ===")
        ssh_exec(
            ssh,
            f"{env} && "
            # Fix symlinks pointing to com.termux absolute paths
            + f"find '{EXPANDED_VENV_DIR}' -type l -exec sh -c "
            + r"""'readlink "$1" | grep -q com.termux && { tgt=$(readlink "$1" | sed "s|com.termux|com.hermux|g"); rm "$1"; ln -sf "$tgt" "$1"; }' _ {} \; && """
            # Fix com.termux → com.hermux in text files (skip binary files)
            + f"find '{EXPANDED_VENV_DIR}' -type f "
            + r"\( -name '*.pyc' -o -name '*.pyo' -o -name '*.so' -o -name '*.a' -o -name '*.opt-*' \) -prune -o "
            + "-type f -exec grep -l 'com\\.termux' {} + 2>/dev/null | "
            + r"while IFS= read -r f; do sed -i 's|/data/data/com\.termux|/data/data/com.hermux|g' \"$f\"; done",
            timeout=300,
        )

        # Step 8b: Normalize permissions and ownership
        print("\n=== Step 8b: Normalize permissions ===")
        ssh_exec(
            ssh,
            f"{env} && "
            + f"find '{EXPANDED_VENV_DIR}' -type d -exec chmod 755 {{}} \\; && "
            + f"find '{EXPANDED_VENV_DIR}' -type f -exec chmod 644 {{}} \\; && "
            + f"chmod 755 '{EXPANDED_VENV_DIR}/bin/'*",
            timeout=60,
        )

        # Step 9: Pack and download
        print("\n=== Step 9: Pack venv ===")
        remote_tar = "$HOME/venv-aarch64.tar.gz"
        ssh_exec(ssh, f"{env} && rm -f {remote_tar} && cd {HERMES_DIR} && tar czf {remote_tar} --owner=0 --group=0 --numeric-owner venv/ && ls -lh {remote_tar}")

        print(f"\n=== Downloading via SFTP to {args.output} ===")
        sftp = ssh.open_sftp()
        remote_path = "/data/data/com.termux/files/home/venv-aarch64.tar.gz"
        stat = sftp.stat(remote_path)
        print(f"Remote size: {stat.st_size / 1024 / 1024:.1f} MB")
        sftp.get(remote_path, args.output)
        sftp.close()

        local_size = os.path.getsize(args.output)
        print(f"Downloaded: {args.output} ({local_size / 1024 / 1024:.1f} MB)")

        # Cleanup remote tar
        ssh_exec(ssh, f"{env} && rm -f {remote_tar}")

        print(f"\n=== Build complete: {args.output} ===")

    finally:
        ssh.close()


if __name__ == "__main__":
    main()
