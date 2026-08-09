use serde::Serialize;

#[derive(Clone, Debug, Serialize)]
pub(crate) struct StatusMessage {
    pub(crate) code: String,
    pub(crate) text: String,
    pub(crate) tone: String,
}

pub(crate) fn ok(code: &str, text: impl Into<String>) -> StatusMessage {
    StatusMessage { code: code.into(), text: text.into(), tone: "ok".into() }
}

pub(crate) fn info(code: &str, text: impl Into<String>) -> StatusMessage {
    StatusMessage { code: code.into(), text: text.into(), tone: "info".into() }
}

pub(crate) fn error(code: &str, text: impl Into<String>) -> StatusMessage {
    StatusMessage { code: code.into(), text: text.into(), tone: "error".into() }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn status_tone_is_explicit_and_serializable() {
        let value = serde_json::to_value(info("loading", "Loading")).unwrap();
        assert_eq!(value["code"], "loading");
        assert_eq!(value["tone"], "info");
    }
}
