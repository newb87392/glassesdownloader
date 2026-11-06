#!/usr/bin/env python3
"""Read the app-private operation journal without UIAutomator or logcat.

Start --wait-next before tapping Connect; a terminal snapshot must carry a
post-watcher start time or follow an observed running ID. Every ADB call
requires an explicit serial.
"""
import argparse
import json
import os
import subprocess
import time
import uuid
from decimal import Decimal, InvalidOperation

ADB = os.environ.get('ADB', 'adb')  # set ADB=/path/to/adb if it is not on PATH
PACKAGE = 'com.open.glassesdownloader'
JOURNAL = 'files/diagnostics/last-operation.json'

def parse_status(text):
    data = json.loads(text)
    if not isinstance(data, dict) or data.get('schema') != 1:
        raise ValueError('Unsupported status journal schema')
    if not isinstance(data.get('session_id'), str) or not data['session_id']:
        raise ValueError('Missing operation ID')
    if data.get('phase') not in ('running', 'terminal') or not isinstance(data.get('status'), str):
        raise ValueError('Invalid phase or status')
    if type(data.get('recorded_at_ms')) is not int or data['recorded_at_ms'] < 0:
        raise ValueError('Invalid timestamp')
    started = data.get('started_at_ms')
    if started is not None and (type(started) is not int or started < 0 or started > data['recorded_at_ms']):
        raise ValueError('Invalid operation start time')
    elapsed = data.get('started_elapsed_ms')
    if elapsed is not None and (type(elapsed) is not int or elapsed < 0):
        raise ValueError('Invalid elapsed start time')
    counts = data.get('summary')
    if counts is not None:
        if not isinstance(counts, dict) or any(type(counts.get(k)) is not int or counts[k] < 0
                                                 for k in ('found', 'downloaded', 'skipped', 'failed')):
            raise ValueError('Invalid summary counts')
        if counts['found'] != sum(counts[k] for k in ('downloaded', 'skipped', 'failed')):
            raise ValueError('Summary counts do not equal library total')
    if data['status'] == 'Transfer complete' and data['phase'] == 'terminal':
        if counts is None or counts['failed'] != 0:
            raise ValueError('Transfer complete without a zero-failure summary')
    if data['phase'] == 'running' and counts is not None:
        raise ValueError('Running operation has terminal summary')
    return data

class StatusWatcher:
    def __init__(self, baseline, started_ms, started_elapsed_ms=None, boot_id=None):
        self.baseline_id = baseline['session_id'] if baseline else None
        self.started_ms = started_ms
        self.started_elapsed_ms = started_elapsed_ms
        self.boot_id = boot_id
        self.running_id = None
        self.running_at_ms = None

    def consume(self, data, current_elapsed_ms=None, current_boot_id=None):
        if self.boot_id is not None and current_boot_id != self.boot_id:
            raise RuntimeError('Device reboot detected; restart the watcher and check the app status')
        if (self.started_elapsed_ms is not None and current_elapsed_ms is not None and
                current_elapsed_ms < self.started_elapsed_ms):
            raise RuntimeError('Device reboot detected; restart the watcher after reconnecting and check the app status')
        if data is None or data['session_id'] == self.baseline_id:
            return None
        started = data.get('started_at_ms')
        elapsed = data.get('started_elapsed_ms')
        if elapsed is not None and self.started_elapsed_ms is not None:
            if elapsed < self.started_elapsed_ms:
                return None
        elif data['recorded_at_ms'] < self.started_ms or (started is not None and started < self.started_ms):
            return None
        if data['phase'] == 'running':
            self.running_id = data['session_id']
            self.running_at_ms = data['recorded_at_ms']
            return None
        # A fast failure may replace the running snapshot between two polls.
        # The terminal carries its original start time so that is still safe.
        if started is not None or elapsed is not None or (data['session_id'] == self.running_id and
                                   data['recorded_at_ms'] >= self.running_at_ms):
            return data
        return None

def read_status(serial):
    proc = subprocess.run([ADB, '-s', serial, 'shell', 'run-as', PACKAGE, 'cat', JOURNAL],
                          capture_output=True, text=True, timeout=15)
    if proc.returncode:
        if 'No such file or directory' in proc.stderr:
            return None
        raise RuntimeError(f'Journal read failed: {proc.stderr[:180]}')
    return parse_status(proc.stdout)

def read_device_elapsed_ms(serial):
    proc = subprocess.run([ADB, '-s', serial, 'shell', 'cat', '/proc/uptime'],
                          capture_output=True, text=True, timeout=15)
    if proc.returncode:
        raise RuntimeError(f'Cannot read device uptime: {proc.stderr[:180]}')
    try:
        seconds = Decimal(proc.stdout.split()[0])
        if not seconds.is_finite() or seconds < 0:
            raise ValueError('uptime must be finite and nonnegative')
        return int(seconds * 1000)
    except (IndexError, ValueError, InvalidOperation) as exc:
        raise RuntimeError('Cannot parse device uptime; retry after checking ADB access') from exc

def read_device_boot_id(serial):
    proc = subprocess.run([ADB, '-s', serial, 'shell', 'cat', '/proc/sys/kernel/random/boot_id'],
                          capture_output=True, text=True, timeout=15)
    if proc.returncode:
        raise RuntimeError(f'Cannot read device boot identifier: {proc.stderr[:180]}')
    value = proc.stdout.strip()
    try:
        if str(uuid.UUID(value)) != value.lower():
            raise ValueError('Noncanonical boot identifier')
    except ValueError as exc:
        raise RuntimeError('Invalid device boot identifier; check ADB access') from exc
    return value

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True, help='Exact adb device serial; never infer a target')
    p.add_argument('--wait-next', action='store_true', help='Wait for a new operation whose start can be verified')
    p.add_argument('--timeout', type=int, default=600)
    args = p.parse_args()
    baseline = read_status(args.serial)
    if not args.wait_next:
        print(json.dumps(baseline))
        return
    boot_at_watch = read_device_boot_id(args.serial)
    elapsed_at_watch = read_device_elapsed_ms(args.serial)
    watcher = StatusWatcher(baseline, started_ms=int(time.time() * 1000),
                            started_elapsed_ms=elapsed_at_watch, boot_id=boot_at_watch)
    print('WATCHING baseline=' + str(watcher.baseline_id), flush=True)
    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        boot_before = read_device_boot_id(args.serial)
        current_elapsed = read_device_elapsed_ms(args.serial)
        current = read_status(args.serial)
        boot_after = read_device_boot_id(args.serial)
        if boot_before != boot_after:
            raise RuntimeError('Device reboot detected during status read; restart watcher and check the app status')
        prior_running = watcher.running_id
        terminal = watcher.consume(current, current_elapsed_ms=current_elapsed, current_boot_id=boot_after)
        if watcher.running_id != prior_running:
            print('RUNNING session=' + str(watcher.running_id), flush=True)
        if terminal:
            print(json.dumps(terminal), flush=True)
            return
        time.sleep(0.25)
    raise TimeoutError('No fresh verifiable terminal operation before timeout; check current journal and retry while watching')

if __name__ == '__main__':
    main()
