from pathlib import Path
import re

# arg0 aliases are best-effort. Android cannot use Rust std File locks,
# so skip the stale-directory janitor and advisory alias lock only on Android.
p = Path("codex/codex-rs/arg0/src/lib.rs")
s = p.read_text()
old = '''    if let Err(err) = janitor_cleanup(&temp_root) {
        eprintln!("WARNING: failed to clean up stale arg0 temp dirs: {err}");
    }'''
new = '''    #[cfg(not(target_os = "android"))]
    if let Err(err) = janitor_cleanup(&temp_root) {
        eprintln!("WARNING: failed to clean up stale arg0 temp dirs: {err}");
    }'''
assert s.count(old) == 1, "arg0 janitor block changed"
s = s.replace(old, new)
old = '''        .open(&lock_path)?;
    lock_file.try_lock()?;'''
new = '''        .open(&lock_path)?;
    #[cfg(not(target_os = "android"))]
    lock_file.try_lock()?;'''
assert s.count(old) == 1, "arg0 alias lock block changed"
p.write_text(s.replace(old, new))

# Keep local log directory creation, but omit unsupported Android cleanup lock.
p = Path("codex/codex-rs/file-system/src/local_logs.rs")
s = p.read_text()
replacements = [
    ("#[cfg(unix)]\n    _cleanup_lock: std::fs::File,",
     '#[cfg(all(unix, not(target_os = "android")))]\n    _cleanup_lock: std::fs::File,'),
    ("                    #[cfg(unix)]\n                    let directory = {",
     '                    #[cfg(all(unix, not(target_os = "android")))]\n                    let directory = {'),
    ("                        #[cfg(unix)]\n                        _cleanup_lock: directory,",
     '                        #[cfg(all(unix, not(target_os = "android")))]\n                        _cleanup_lock: directory,'),
]
for old, new in replacements:
    assert s.count(old) == 1, f"local logs source changed: {old!r}"
    s = s.replace(old, new)
p.write_text(s)

# Use Linux flock on Android to preserve real cross-thread/process writer locking.
p = Path("codex/codex-rs/rollout/src/writer_lock.rs")
s = p.read_text()
s = re.sub(r"([A-Za-z_][A-Za-z0-9_]*)\.try_lock_shared\(\)", r"try_lock_shared(&\1)", s)
s = re.sub(r"([A-Za-z_][A-Za-z0-9_]*)\.try_lock\(\)", r"try_lock_exclusive(&\1)", s)
assert ".try_lock()" not in s and ".try_lock_shared()" not in s, "writer lock call replacement incomplete"
marker = 'const COORDINATION_LOCK_FILE: &str = ".coordination.lock";'
helpers = marker + '''

#[cfg(target_os = "android")]
fn android_flock(file: &File, operation: libc::c_int) -> Result<(), fs::TryLockError> {
    use std::os::fd::AsRawFd;
    let result = unsafe { libc::flock(file.as_raw_fd(), operation | libc::LOCK_NB) };
    if result == 0 {
        return Ok(());
    }
    let error = io::Error::last_os_error();
    if error.kind() == io::ErrorKind::WouldBlock {
        Err(fs::TryLockError::WouldBlock)
    } else {
        Err(fs::TryLockError::Error(error))
    }
}

fn try_lock_exclusive(file: &File) -> Result<(), fs::TryLockError> {
    #[cfg(target_os = "android")]
    { android_flock(file, libc::LOCK_EX) }
    #[cfg(not(target_os = "android"))]
    { std::fs::File::try_lock(file) }
}

fn try_lock_shared(file: &File) -> Result<(), fs::TryLockError> {
    #[cfg(target_os = "android")]
    { android_flock(file, libc::LOCK_SH) }
    #[cfg(not(target_os = "android"))]
    { std::fs::File::try_lock_shared(file) }
}
'''
assert s.count(marker) == 1, "writer lock helper insertion point changed"
p.write_text(s.replace(marker, helpers))


# Codex installation ID is read during App Server startup. Rust std File locks
# return Unsupported on Android; use Android's kernel flock implementation.
p = Path("codex/codex-rs/runtime-agent-instance/Cargo.toml")
cargo = p.read_text()
if '[target."cfg(target_family = \\"unix\\")".dependencies]' not in cargo:
    cargo += '\n[target."cfg(target_family = \\"unix\\")".dependencies]\nlibc = { workspace = true }\n'
p.write_text(cargo)

p = Path("codex/codex-rs/runtime-agent-instance/src/installation_id.rs")
s = p.read_text()
if "fn android_file_lock" not in s:
    s = s.replace(
        "use std::fs::OpenOptions;\n",
        "use std::fs::File;\nuse std::fs::OpenOptions;\n"
    )
    marker = "const INSTALLATION_ID_FILENAME: &str = \\"installation_id\\";"
    helpers = marker + """

#[cfg(target_os = "android")]
fn android_file_lock(file: &File, operation: libc::c_int) -> Result<()> {
    use std::os::fd::AsRawFd;
    if unsafe { libc::flock(file.as_raw_fd(), operation) } == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error())
    }
}

fn lock_shared(file: &File) -> Result<()> {
    #[cfg(target_os = "android")]
    { android_file_lock(file, libc::LOCK_SH) }
    #[cfg(not(target_os = "android"))]
    { file.lock_shared() }
}

fn lock_exclusive(file: &File) -> Result<()> {
    #[cfg(target_os = "android")]
    { android_file_lock(file, libc::LOCK_EX) }
    #[cfg(not(target_os = "android"))]
    { file.lock() }
}

fn unlock_file(file: &File) -> Result<()> {
    #[cfg(target_os = "android")]
    { android_file_lock(file, libc::LOCK_UN) }
    #[cfg(not(target_os = "android"))]
    { file.unlock() }
}
"""
    assert s.count(marker) == 1, "installation ID lock helper insertion point changed"
    s = s.replace(marker, helpers)
    s = s.replace("file.lock_shared()?;", "lock_shared(&file)?;")
    s = s.replace("file.unlock()?;", "unlock_file(&file)?;")
    s = s.replace("file.lock()?;", "lock_exclusive(&file)?;")
    p.write_text(s)
