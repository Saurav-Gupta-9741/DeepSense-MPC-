import subprocess
import zipfile
import os

apk_path = r"e:\GenAI-part2-Rag-implementation-main\PervasiveSense-debug.apk"
aapt2_path = r"C:\Android\build-tools\34.0.0\aapt2.exe"

def run_aapt2(apk, aapt2):
    try:
        result = subprocess.run([aapt2, "dump", "badging", apk], capture_output=True, text=True, check=True)
        return result.stdout
    except Exception as e:
        print(f"FAIL: aapt2 execution failed: {e}")
        return ""

def verify_aapt2_output(output):
    print("--- AAPT2 Checks ---")
    if "package: name='com.iitj.pervasivesense'" in output:
        print("PASS: Package name is com.iitj.pervasivesense")
    else:
        print("FAIL: Package name mismatch or not found")
        
    if "sdkVersion:" in output:
        print("PASS: minSdkVersion found")
    else:
        print("FAIL: minSdkVersion missing")
        
    if "targetSdkVersion:" in output:
        print("PASS: targetSdkVersion found")
    else:
        print("FAIL: targetSdkVersion missing")
        
    if "uses-permission:" in output:
        print("PASS: Permissions found")
    else:
        print("FAIL: Permissions missing")
        
    if "launchable-activity:" in output or "activity:" in output:
        print("PASS: Activities found")
    else:
        print("FAIL: Activities missing")
        
    if "application-label:" in output:
        print("PASS: Application label found")
    else:
        print("FAIL: Application label missing")

def inspect_apk_zip(apk):
    print("--- ZIP Content Checks ---")
    try:
        with zipfile.ZipFile(apk, 'r') as z:
            namelist = z.namelist()
            
            # classes.dex
            if "classes.dex" in namelist:
                print("PASS: classes.dex exists")
            else:
                print("FAIL: classes.dex missing")
                
            # assets/deepsense_int8.tflite
            if "assets/deepsense_int8.tflite" in namelist:
                info = z.getinfo("assets/deepsense_int8.tflite")
                size_kb = info.file_size / 1024
                if 85 <= size_kb <= 95:
                    print(f"PASS: assets/deepsense_int8.tflite exists and size is OK (~{size_kb:.1f}KB)")
                else:
                    print(f"FAIL: assets/deepsense_int8.tflite exists but wrong size: {size_kb:.1f}KB")
            else:
                print("FAIL: assets/deepsense_int8.tflite missing")
                
            # Native libraries
            so_files = [f for f in namelist if f.endswith(".so")]
            if so_files:
                print(f"PASS: Native libraries found ({len(so_files)} files)")
                if any("libtensorflowlite_jni.so" in f for f in so_files):
                    print("PASS: libtensorflowlite_jni.so found")
                else:
                    print("FAIL: libtensorflowlite_jni.so missing")
            else:
                print("FAIL: Native libraries missing")
                
            # Layouts
            layouts = [f for f in namelist if f.startswith("res/layout")]
            if layouts:
                print(f"PASS: Layout XMLs compiled ({len(layouts)} files)")
            else:
                print("FAIL: Layout XMLs missing")
                
    except Exception as e:
        print(f"FAIL: ZIP inspection failed: {e}")

def check_size(apk):
    print("--- Size Checks ---")
    try:
        size_mb = os.path.getsize(apk) / (1024 * 1024)
        if size_mb < 25:
            print(f"PASS: APK size is reasonable ({size_mb:.2f}MB < 25MB)")
        else:
            print(f"FAIL: APK size is too large ({size_mb:.2f}MB)")
    except Exception as e:
        print(f"FAIL: Size check failed: {e}")

def check_signature(apk):
    print("--- Signature Checks ---")
    apksigner_path = r"C:\Android\build-tools\34.0.0\apksigner.bat"
    if os.path.exists(apksigner_path):
        try:
            res = subprocess.run([apksigner_path, "verify", "--print-certs", apk], capture_output=True, text=True)
            if res.returncode == 0:
                print("PASS: APK signature is valid (apksigner)")
            else:
                print("FAIL: APK signature verification failed")
        except Exception as e:
            print(f"FAIL: apksigner failed to run: {e}")
    else:
        try:
            res = subprocess.run(["jarsigner", "-verify", "-certs", apk], capture_output=True, text=True)
            if "jar verified" in res.stdout:
                print("PASS: APK signature is valid (jarsigner)")
            else:
                print("FAIL: APK signature verification failed (jarsigner)")
        except Exception as e:
            print(f"FAIL: signature verification failed: {e}")

if __name__ == '__main__':
    if not os.path.exists(apk_path):
        print(f"FAIL: APK not found at {apk_path}")
    else:
        out = run_aapt2(apk_path, aapt2_path)
        if out:
            verify_aapt2_output(out)
        inspect_apk_zip(apk_path)
        check_size(apk_path)
        check_signature(apk_path)
