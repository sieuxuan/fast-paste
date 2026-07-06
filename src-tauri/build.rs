fn main() {
    println!("cargo:rerun-if-changed=../google_oauth.json");
    if let Ok(content) = std::fs::read_to_string("../google_oauth.json") {
        if let Some(client_id) = extract_json_value(&content, "desktopClientId") {
            println!("cargo:rustc-env=FASTPASTE_GOOGLE_DESKTOP_CLIENT_ID={}", client_id);
        }
        if let Some(client_secret) = extract_json_value(&content, "desktopClientSecret") {
            println!("cargo:rustc-env=FASTPASTE_GOOGLE_DESKTOP_CLIENT_SECRET={}", client_secret);
        }
    }
    tauri_build::build()
}

fn extract_json_value(json: &str, key: &str) -> Option<String> {
    let key_pattern = format!("\"{}\"", key);
    let idx = json.find(&key_pattern)?;
    let start_search = idx + key_pattern.len();
    let colon_idx = json[start_search..].find(':')?;
    let quote_start = json[start_search + colon_idx..].find('"')?;
    let actual_start = start_search + colon_idx + quote_start + 1;
    let quote_end = json[actual_start..].find('"')?;
    let val = &json[actual_start..actual_start + quote_end];
    Some(val.to_string())
}

