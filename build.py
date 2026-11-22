import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

TOOLS_DIR = Path("C:/spoox_tools")
JDK_DIR = TOOLS_DIR / "jdk17"
GRADLE_DIR = TOOLS_DIR / "gradle"
SDK_DIR = TOOLS_DIR / "android-sdk"
WORK_PROJ = TOOLS_DIR / "project"

URLS = {
    "jdk": "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk",
    "gradle": "https://services.gradle.org/distributions/gradle-8.5-bin.zip",
    "cmdline": "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip",
}


def download(url: str, dest: Path, label: str):
    if dest.exists():
        print(f"[OK] {label} уже скачан.")
        return
    print(f"[...] Скачиваю {label}...")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req) as resp, open(dest, "wb") as out:
        total = int(resp.headers.get("Content-Length", 0))
        done = 0
        while True:
            chunk = resp.read(1024 * 256)
            if not chunk:
                break
            out.write(chunk)
            done += len(chunk)
            if total > 0:
                pct = done * 100 // total
                print(f"\r    {pct}% ({done // (1024*1024)} / {total // (1024*1024)} MB)", end="", flush=True)
    print(f"\n[OK] {label} скачан.")


def extract(zip_path: Path, target_dir: Path, label: str):
    print(f"[...] Распаковываю {label}...")
    target_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path, "r") as zf:
        zf.extractall(target_dir)
    print(f"[OK] {label} распакован.")


def find_project_root() -> Path:
    cwd = Path.cwd()
    candidates = [cwd, cwd / "spoox_chess_v2_1", cwd / "spoox_chess_v2_6", cwd / "spoox_chess_v2_5", cwd / "spoox_chess_android"]
    for c in candidates:
        if (c / "settings.gradle.kts").exists():
            return c
    for p in cwd.rglob("settings.gradle.kts"):
        return p.parent
    print("[ОШИБКА] Не найден файл settings.gradle.kts. Положи скрипт рядом с папкой проекта.")
    sys.exit(1)


def find_single_subdir(parent: Path, marker: str) -> Path:
    if (parent / marker).exists():
        return parent
    for sub in parent.iterdir():
        if sub.is_dir() and (sub / marker).exists():
            return sub
    raise RuntimeError(f"Не найдено {marker} внутри {parent}")



def install_stockfish(proj_dir: Path):
    jni_dir = proj_dir / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
    sf_so = jni_dir / "libstockfish.so"
    if sf_so.exists() and sf_so.stat().st_size > 1_000_000:
        with open(sf_so, "rb") as f:
            if f.read(4) == b"\x7fELF":
                print("[OK] Stockfish ARM64 уже подключён (ELF проверен).")
                return

    jni_dir.mkdir(parents=True, exist_ok=True)
    sf_urls = [
        ("stockfish-android-armv8-dotprod.tar", "https://github.com/official-stockfish/Stockfish/releases/latest/download/stockfish-android-armv8-dotprod.tar"),
        ("stockfish-android-armv8.tar", "https://github.com/official-stockfish/Stockfish/releases/latest/download/stockfish-android-armv8.tar"),
        ("stockfish-17-android-armv8-dotprod.tar", "https://github.com/official-stockfish/Stockfish/releases/download/sf_17/stockfish-android-armv8-dotprod.tar"),
        ("stockfish-17-android-armv8.tar", "https://github.com/official-stockfish/Stockfish/releases/download/sf_17/stockfish-android-armv8.tar"),
    ]
    import tarfile
    for fname, url in sf_urls:
        sf_tar = TOOLS_DIR / fname
        try:
            download(url, sf_tar, f"Stockfish ARM64 ({fname})")
            with tarfile.open(sf_tar, "r:*") as tf:
                candidates = [
                    m for m in tf.getmembers()
                    if m.isfile() and m.size > 1_000_000 and not m.name.endswith((".txt", ".md", ".nnue"))
                ]
                candidates.sort(key=lambda m: m.size, reverse=True)
                for member in candidates:
                    fobj = tf.extractfile(member)
                    if fobj is None:
                        continue
                    data = fobj.read()
                    if data[:4] == b"\x7fELF":
                        with open(sf_so, "wb") as dst:
                            dst.write(data)
                        print(f"[OK] Stockfish ARM64 успешно встроен: {sf_so} ({len(data)//(1024*1024)} MB)")
                        return
        except Exception as e:
            print(f"[!] Попытка {fname} не удалась ({e}), пробую следующий вариант...")
    print("[!] Не удалось скачать Stockfish — APK соберётся с встроенным SpooxEngine.")

def main():
    TOOLS_DIR.mkdir(parents=True, exist_ok=True)
    proj_src = find_project_root()
    print(f"[OK] Найден проект: {proj_src}")

    jdk_zip = TOOLS_DIR / "jdk17.zip"
    gradle_zip = TOOLS_DIR / "gradle.zip"
    cmd_zip = TOOLS_DIR / "cmdline.zip"

    if not JDK_DIR.exists():
        download(URLS["jdk"], jdk_zip, "JDK 17")
        extract(jdk_zip, JDK_DIR, "JDK 17")

    if not GRADLE_DIR.exists():
        download(URLS["gradle"], gradle_zip, "Gradle 8.5")
        extract(gradle_zip, GRADLE_DIR, "Gradle 8.5")

    latest_dir = SDK_DIR / "cmdline-tools" / "latest"
    if not (latest_dir / "bin" / "sdkmanager.bat").exists():
        download(URLS["cmdline"], cmd_zip, "Android Command Line Tools")
        tmp_cmd = TOOLS_DIR / "tmp_cmd"
        if tmp_cmd.exists():
            shutil.rmtree(tmp_cmd)
        extract(cmd_zip, tmp_cmd, "Android Command Line Tools")
        src_cmd = find_single_subdir(tmp_cmd, "bin/sdkmanager.bat")
        latest_dir.parent.mkdir(parents=True, exist_ok=True)
        if latest_dir.exists():
            shutil.rmtree(latest_dir)
        shutil.move(str(src_cmd), str(latest_dir))
        shutil.rmtree(tmp_cmd, ignore_errors=True)

    java_home = find_single_subdir(JDK_DIR, "bin/java.exe")
    gradle_home = find_single_subdir(GRADLE_DIR, "bin/gradle.bat")
    sdkmanager = latest_dir / "bin" / "sdkmanager.bat"
    gradle_bat = gradle_home / "bin" / "gradle.bat"

    env = os.environ.copy()
    env["JAVA_HOME"] = str(java_home)
    env["ANDROID_HOME"] = str(SDK_DIR)
    env["ANDROID_SDK_ROOT"] = str(SDK_DIR)
    env["PATH"] = f"{java_home / 'bin'};{gradle_home / 'bin'};{latest_dir / 'bin'};{env.get('PATH', '')}"

    if not (SDK_DIR / "platforms" / "android-34").exists():
        print("[...] Принимаю лицензии Android SDK...")
        subprocess.run(
            [str(sdkmanager), "--licenses"],
            input=("y\n" * 30).encode(),
            env=env,
            check=False,
        )
        print("[...] Устанавливаю Android SDK API 34 и Build-Tools...")
        subprocess.run(
            [str(sdkmanager), "platforms;android-34", "build-tools;34.0.0", "platform-tools"],
            input=("y\n" * 10).encode(),
            env=env,
            check=True,
        )

    print(f"[...] Копирую проект в {WORK_PROJ} (путь без русских букв)...")
    if WORK_PROJ.exists():
        shutil.rmtree(WORK_PROJ)
    shutil.copytree(proj_src, WORK_PROJ)

    install_stockfish(WORK_PROJ)
    print("[...] Запускаю сборку APK (gradle assembleDebug)...")
    res = subprocess.run([str(gradle_bat), "assembleDebug"], cwd=str(WORK_PROJ), env=env)
    if res.returncode != 0:
        print("\n[ОШИБКА] Сборка не удалась. Посмотри текст ошибки выше.")
        sys.exit(res.returncode)

    apk_src = WORK_PROJ / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    apk_dst = Path.cwd() / "SpooxChess-debug.apk"
    shutil.copy2(apk_src, apk_dst)
    print(f"\n[ГОТОВО] APK успешно собран: {apk_dst}")


if __name__ == "__main__":
    main()
