#!/usr/bin/env python3

import subprocess
import time
import argparse
import sys
import os
import xml.etree.ElementTree as ET
from datetime import datetime

def run_cmd(cmd):
    """Run a shell command and return its output."""
    try:
        result = subprocess.run(cmd, shell=True, check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        return result.stdout.strip(), result.stderr.strip()
    except Exception as e:
        return "", str(e)

def get_pid(package):
    """Get the current process ID of a package."""
    stdout, _ = run_cmd(f"adb shell pidof {package}")
    if stdout:
        return stdout.split()[0]
    return None

def clear_logcat():
    run_cmd("adb logcat -c")

def get_crash_log(package):
    """Extract crash logs from logcat for the given package or general native tombstones."""
    cmd = f"adb logcat -d -v threadtime -b crash,main"
    stdout, _ = run_cmd(cmd)
    return stdout

def get_apps_from_xml():
    """Parse recommendations from arrays.xml dynamically."""
    xml_path = "app/src/main/res/values/arrays.xml"
    apps = []
    if not os.path.exists(xml_path):
        return apps
        
    try:
        tree = ET.parse(xml_path)
        root = tree.getroot()
        for array in root.findall('string-array'):
            if array.get('name') == 'xposed_scope':
                for item in array.findall('item'):
                    if item.text:
                        apps.append(item.text.strip())
    except Exception as e:
        print(f"Failed to parse XML: {e}")
    return apps

def test_package(package, wait_time, interactions, log_dir):
    print(f"\n[{package}] Starting test...")
    
    run_cmd(f"adb shell am force-stop {package}")
    clear_logcat()
    
    print(f"[{package}] Launching app...")
    stdout, stderr = run_cmd(f"adb shell monkey -p {package} -c android.intent.category.LAUNCHER 1")
    if "No activities found" in stderr or "No activities found" in stdout:
        print(f"[{package}] ERROR: App not installed. Good for you I guess?")
        return 0
        
    time.sleep(wait_time)
    
    pid = get_pid(package)
    if not pid:
        print(f"[{package}] CRASHED: App died immediately after launch!")
        return detect_and_save_crash(package, log_dir)
        
    print(f"[{package}] App launched successfully! PID: {pid}. Running {interactions} random UI interactions...")
    
    run_cmd(f"adb shell monkey -p {package} --throttle 200 {interactions}")
    time.sleep(2)
    
    pid = get_pid(package)
    if not pid:
        print(f"[{package}] CRASHED: App died shortly after interactions!")
        return detect_and_save_crash(package, log_dir)
        
    print(f"[{package}] OK: App survived the test.")
    run_cmd(f"adb shell am force-stop {package}")
    return 1

def detect_and_save_crash(package, log_dir):
    print(f"[{package}] Capturing crash logcat...")
    time.sleep(2)
    
    crash_data = get_crash_log(package)
    if not crash_data:
        crash_data, _ = run_cmd("adb logcat -d -t 500")
        
    filename = os.path.join(log_dir, f"crash_report_{package}.log")
    with open(filename, "w") as f:
        f.write(f"CRASH REPORT FOR {package}\n")
        f.write("="*40 + "\n")
        f.write(crash_data)
        
    print(f"[{package}] Crash report saved to '{filename}'")
    return -1

def main():
    parser = argparse.ArgumentParser(description="ADB Automated App Crash Tester")
    parser.add_argument("-f", "--file", type=str, help="Text file containing package names (one per line)")
    parser.add_argument("-p", "--packages", type=str, nargs="+", help="Space-separated list of package names")
    parser.add_argument("-w", "--wait", type=int, default=5, help="Seconds to wait after launch before simulating touches (default: 5)")
    parser.add_argument("-i", "--interactions", type=int, default=50, help="Number of random monkey touch events to send (default: 50)")

    args = parser.parse_args()
    
    packages = []
    if args.packages:
        packages.extend(args.packages)
    
    if args.file and os.path.exists(args.file):
        with open(args.file, 'r') as f:
            for line in f:
                pkg = line.strip()
                if pkg and not pkg.startswith("#"):
                    packages.append(pkg)
                    
    if not packages:
        print("Fetching recommended target apps from arrays.xml...")
        packages = get_apps_from_xml()
        
    if not packages:
        print("ERROR: No packages to test. Please pass them manually or ensure arrays.xml exists.")
        sys.exit(1)
        
    print(f"Testing {len(packages)} packages...")
    
    res, _ = run_cmd("adb devices")
    if "device" not in res:
        print("ERROR: No adb devices found. Make sure your phone is connected and USB debugging is enabled.")
        sys.exit(1)
        
    # Create dated log directory
    date_str = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    log_dir = os.path.join("test_logs", date_str)
    os.makedirs(log_dir, exist_ok=True)
    print(f"Logs will be saved to: {log_dir}/")
        
    failed = []
    missing = []
    
    for pkg in packages:
        result = test_package(pkg, args.wait, args.interactions, log_dir)
        if result == 1:
            pass
        elif result == 0:
            missing.append(pkg)
        elif result == -1:
            failed.append(pkg)
            
    print("\n" + "="*40)
    print("TEST SUMMARY")
    print("="*40)
    print(f"Total apps tested: {len(packages)}")
    print(f"Passed: {len(packages) - len(failed) - len(missing)}")
    print(f"Failed: {len(failed)}")
    print(f"Missing: {len(missing)}")
    for f in failed:
        print(f"  - {f} (See {log_dir}/crash_report_{f}.log)")
    for m in missing:
        print(f"  - {m} (Not installed)")

if __name__ == "__main__":
    main()
