use std::path::{Path, PathBuf};

const DPAPI_DESCRIPTION: &[u16] = &[
    'F' as u16, 'a' as u16, 's' as u16, 't' as u16, 'P' as u16, 'a' as u16, 's' as u16, 't' as u16,
    'e' as u16, 0,
];

#[cfg(windows)]
pub(crate) fn protect(plain: &[u8]) -> Result<Vec<u8>, String> {
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{
        CryptProtectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
    };

    let input = CRYPT_INTEGER_BLOB {
        cbData: plain
            .len()
            .try_into()
            .map_err(|_| "Dữ liệu vault quá lớn.")?,
        pbData: plain.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    let ok = unsafe {
        CryptProtectData(
            &input,
            DPAPI_DESCRIPTION.as_ptr(),
            std::ptr::null(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if ok == 0 {
        return Err(format!(
            "DPAPI không mã hoá được dữ liệu: {}",
            std::io::Error::last_os_error()
        ));
    }
    let encrypted =
        unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize) }.to_vec();
    unsafe { LocalFree(output.pbData.cast()) };
    Ok(encrypted)
}

#[cfg(windows)]
pub(crate) fn unprotect(encrypted: &[u8]) -> Result<Vec<u8>, String> {
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{
        CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
    };

    let input = CRYPT_INTEGER_BLOB {
        cbData: encrypted
            .len()
            .try_into()
            .map_err(|_| "Dữ liệu vault quá lớn.")?,
        pbData: encrypted.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    let ok = unsafe {
        CryptUnprotectData(
            &input,
            std::ptr::null_mut(),
            std::ptr::null(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if ok == 0 {
        return Err(format!(
            "DPAPI không giải mã được dữ liệu: {}",
            std::io::Error::last_os_error()
        ));
    }
    let plain =
        unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize) }.to_vec();
    unsafe { LocalFree(output.pbData.cast()) };
    Ok(plain)
}

#[cfg(not(windows))]
pub(crate) fn protect(_plain: &[u8]) -> Result<Vec<u8>, String> {
    Err("DPAPI chỉ khả dụng trên Windows.".to_string())
}

#[cfg(not(windows))]
pub(crate) fn unprotect(_encrypted: &[u8]) -> Result<Vec<u8>, String> {
    Err("DPAPI chỉ khả dụng trên Windows.".to_string())
}

pub(crate) fn write_protected_atomic(path: &Path, plain: &[u8]) -> Result<(), String> {
    let encrypted = protect(plain)?;
    write_atomic(path, &encrypted)
}

pub(crate) fn read_protected(path: &Path) -> Result<Vec<u8>, String> {
    let primary = std::fs::read(path)
        .ok()
        .and_then(|bytes| unprotect(&bytes).ok());
    if let Some(plain) = primary {
        return Ok(plain);
    }
    let backup = path.with_extension("bak");
    let encrypted = std::fs::read(&backup).map_err(|error| {
        format!(
            "Không đọc được vault {} hoặc backup: {error}",
            path.display()
        )
    })?;
    unprotect(&encrypted)
}

pub(crate) fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), String> {
    use std::io::Write;
    let tmp = temporary_path(path);
    let result = (|| -> Result<(), String> {
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&tmp)
            .map_err(|error| format!("Không tạo được {}: {error}", tmp.display()))?;
        file.write_all(bytes)
            .and_then(|_| file.sync_all())
            .map_err(|error| format!("Không ghi được {}: {error}", tmp.display()))?;
        drop(file);
        if path.exists() {
            std::fs::copy(path, path.with_extension("bak"))
                .map_err(|error| format!("Không tạo được backup {}: {error}", path.display()))?;
        }
        replace_file(&tmp, path)
            .map_err(|error| format!("Không hoàn tất {}: {error}", path.display()))
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    result
}

#[cfg(windows)]
fn replace_file(source: &Path, target: &Path) -> std::io::Result<()> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::{
        MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
    };
    let source: Vec<u16> = source.as_os_str().encode_wide().chain(Some(0)).collect();
    let target: Vec<u16> = target.as_os_str().encode_wide().chain(Some(0)).collect();
    if unsafe {
        MoveFileExW(
            source.as_ptr(),
            target.as_ptr(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    } == 0
    {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(())
    }
}

#[cfg(not(windows))]
fn replace_file(source: &Path, target: &Path) -> std::io::Result<()> {
    std::fs::rename(source, target)
}

pub(crate) fn read_json_with_backup<T: serde::de::DeserializeOwned>(
    path: &Path,
) -> Result<T, String> {
    for candidate in [path.to_path_buf(), path.with_extension("bak")] {
        if let Ok(bytes) = std::fs::read(candidate) {
            if let Ok(value) = serde_json::from_slice(&bytes) {
                return Ok(value);
            }
        }
    }
    Err(format!(
        "Không đọc được JSON {} hoặc backup.",
        path.display()
    ))
}

fn temporary_path(path: &Path) -> PathBuf {
    let mut name = path.as_os_str().to_os_string();
    name.push(format!(".{}.tmp", rand::random::<u64>()));
    PathBuf::from(name)
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;

    #[test]
    fn locked_file_keeps_original_then_retries_without_tmp_leaks() {
        use std::os::windows::fs::OpenOptionsExt;
        let directory =
            std::env::temp_dir().join(format!("fastpaste-save-test-{}", rand::random::<u64>()));
        std::fs::create_dir(&directory).unwrap();
        let path = directory.join("state.json");
        write_atomic(&path, br#"{"value":1}"#).unwrap();
        let locked = std::fs::OpenOptions::new()
            .read(true)
            .share_mode(0)
            .open(&path)
            .unwrap();
        assert!(write_atomic(&path, br#"{"value":2}"#).is_err());
        drop(locked);
        assert_eq!(std::fs::read(&path).unwrap(), br#"{"value":1}"#);
        write_atomic(&path, br#"{"value":2}"#).unwrap();
        assert_eq!(
            std::fs::read(path.with_extension("bak")).unwrap(),
            br#"{"value":1}"#
        );
        assert_eq!(std::fs::read(&path).unwrap(), br#"{"value":2}"#);
        std::fs::write(&path, b"broken-json").unwrap();
        let recovered: serde_json::Value = read_json_with_backup(&path).unwrap();
        assert_eq!(recovered["value"], 1);
        assert!(!std::fs::read_dir(&directory).unwrap().any(|entry| entry
            .unwrap()
            .file_name()
            .to_string_lossy()
            .ends_with(".tmp")));
        for file in std::fs::read_dir(&directory).unwrap() {
            std::fs::remove_file(file.unwrap().path()).unwrap();
        }
        std::fs::remove_dir(directory).unwrap();
    }

    #[test]
    fn dpapi_round_trip_and_rejects_tampering() {
        let plain = b"FastPaste vault \x00 with binary";
        let mut encrypted = protect(plain).unwrap();
        assert_ne!(encrypted, plain);
        assert_eq!(unprotect(&encrypted).unwrap(), plain);
        let last = encrypted.len() - 1;
        encrypted[last] ^= 0x40;
        assert!(unprotect(&encrypted).is_err());
    }
}
