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

pub(crate) fn read_with_backup(path: &Path) -> Result<Vec<u8>, String> {
    std::fs::read(path)
        .or_else(|_| std::fs::read(path.with_extension("bak")))
        .map_err(|error| format!("Không đọc được {} hoặc backup: {error}", path.display()))
}

pub(crate) fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let tmp = temporary_path(path);
    std::fs::write(&tmp, bytes)
        .map_err(|error| format!("Không ghi được {}: {error}", tmp.display()))?;
    if path.exists() {
        let backup = path.with_extension("bak");
        let _ = std::fs::copy(path, backup);
        std::fs::remove_file(path)
            .map_err(|error| format!("Không thay được {}: {error}", path.display()))?;
    }
    if let Err(error) = std::fs::rename(&tmp, path) {
        let backup = path.with_extension("bak");
        if !path.exists() && backup.exists() {
            let _ = std::fs::copy(&backup, path);
        }
        return Err(format!("Không hoàn tất {}: {error}", path.display()));
    }
    Ok(())
}

fn temporary_path(path: &Path) -> PathBuf {
    let mut name = path.as_os_str().to_os_string();
    name.push(".tmp");
    PathBuf::from(name)
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;

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
