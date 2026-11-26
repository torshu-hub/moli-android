//! The connection to Netatmo: an access token (3 hours) renewed from the
//! `refresh_token`, which Netatmo replaces at every renewal.
//!
//! The way Home Assistant does it (it keeps the new token in its config
//! entry at each renewal) and the official camera driver (PR #331): the
//! secrets are the same (`client_id`, `client_secret`, `refresh_token`), the
//! new `refresh_token` goes back to the vault at once, and only an explicit
//! `invalid_grant` says the connection is dead. A secret that looks damaged,
//! a quota or a network error erase nothing.

use std::time::Duration;

use anyhow::{Context as _, bail};
use http::{Method, Request, StatusCode};
use http_body_util::Full;
use moli_net::Body as Bytes;
use moli_runtime::DriverCtx;
use serde::Deserialize;
use serde_json::Value as Json;
use tokio::time::Instant;

pub(crate) const HOST: &str = "api.netatmo.com";
const USER_AGENT: &str = "MoliOS/0.1";
pub(crate) const CLIENT_ID_KEY: &str = "client_id";
pub(crate) const CLIENT_SECRET_KEY: &str = "client_secret";
pub(crate) const REFRESH_KEY: &str = "refresh_token";
const HTTP_LIMIT: Duration = Duration::from_secs(15);
const MAX_BODY: usize = 4 * 1024 * 1024;
/// A token is renewed this long before it ends.
const MARGIN: Duration = Duration::from_secs(300);

#[derive(Debug, Deserialize)]
struct TokenPair {
    access_token: String,
    #[serde(default)]
    refresh_token: String,
    #[serde(default = "default_lifetime")]
    expires_in: u64,
}

const fn default_lifetime() -> u64 {
    10_800
}

/// What Netatmo answered to a renewal.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Outcome {
    Renewed {
        access: String,
        refresh: String,
        lifetime: Duration,
    },
    /// `invalid_grant`: the `refresh_token` is dead, the person connects again.
    Revoked(String),
}

pub(crate) fn form_encode(pairs: &[(&str, &str)]) -> String {
    let mut out = String::new();
    for (i, (k, v)) in pairs.iter().enumerate() {
        if i > 0 {
            out.push('&');
        }
        out.push_str(k);
        out.push('=');
        for b in v.bytes() {
            if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
                out.push(char::from(b));
            } else {
                use std::fmt::Write as _;
                let _ = write!(out, "%{b:02X}");
            }
        }
    }
    out
}

/// A secret with a space, a shell character or nothing in it: pasted badly.
pub(crate) fn looks_corrupted(secret: &str) -> bool {
    secret.is_empty()
        || secret.len() > 256
        || secret
            .chars()
            .any(|c| c.is_whitespace() || matches!(c, ';' | '$' | '"' | '\'' | '`'))
}

/// Only an explicit `invalid_grant` means the `refresh_token` is dead.
pub(crate) fn is_invalid_grant(status: StatusCode, body: &[u8]) -> bool {
    status == StatusCode::BAD_REQUEST
        && serde_json::from_slice::<Json>(body)
            .is_ok_and(|j| j["error"].as_str() == Some("invalid_grant"))
}

fn token_request(body: String) -> anyhow::Result<Request<Full<Bytes>>> {
    let raw = body.into_bytes();
    Ok(Request::builder()
        .method(Method::POST)
        .uri("/oauth2/token")
        .header("user-agent", USER_AGENT)
        .header(
            "content-type",
            "application/x-www-form-urlencoded;charset=UTF-8",
        )
        .header("content-length", raw.len().to_string())
        .header("accept", "application/json")
        .body(Full::new(Bytes::from(raw)))?)
}

pub(crate) async fn renew(
    client_id: &str,
    client_secret: &str,
    refresh_token: &str,
) -> anyhow::Result<Outcome> {
    if looks_corrupted(client_id)
        || looks_corrupted(client_secret)
        || looks_corrupted(refresh_token)
    {
        bail!("netatmo: a secret looks damaged (space, empty…); nothing was erased");
    }
    let body = form_encode(&[
        ("grant_type", "refresh_token"),
        ("client_id", client_id),
        ("client_secret", client_secret),
        ("refresh_token", refresh_token),
    ]);
    let (status, bytes) =
        moli_net::web(HOST, 443, true, token_request(body)?, HTTP_LIMIT, MAX_BODY).await?;
    let detail = || String::from_utf8_lossy(&bytes[..bytes.len().min(200)]).into_owned();
    if is_invalid_grant(status, &bytes) {
        return Ok(Outcome::Revoked(format!(
            "HTTP {} {}",
            status.as_u16(),
            detail()
        )));
    }
    if !status.is_success() {
        bail!(
            "netatmo oauth2/token: HTTP {} {}",
            status.as_u16(),
            detail()
        );
    }
    let pair: TokenPair =
        serde_json::from_slice(&bytes).context("invalid netatmo token response")?;
    Ok(Outcome::Renewed {
        access: pair.access_token,
        refresh: pair.refresh_token,
        lifetime: Duration::from_secs(pair.expires_in),
    })
}

/// The tokens of one instance, kept while the driver runs.
#[derive(Debug)]
pub(crate) struct Tokens {
    client_id: String,
    client_secret: String,
    /// The `refresh_token` in force: the vault's, then each new one.
    refresh: String,
    /// A new `refresh_token` the vault has not taken yet: tried again at the
    /// next call, since Netatmo no longer honours the old one.
    unsaved: bool,
    access: Option<(String, Instant)>,
}

impl Tokens {
    /// `None` when a secret is missing.
    pub(crate) fn load(ctx: &DriverCtx) -> Option<Self> {
        Some(Self {
            client_id: ctx.secret(CLIENT_ID_KEY)?,
            client_secret: ctx.secret(CLIENT_SECRET_KEY)?,
            refresh: ctx.secret(REFRESH_KEY)?,
            unsaved: false,
            access: None,
        })
    }

    /// The names of the secrets the vault lacks, for the message.
    pub(crate) fn missing(ctx: &DriverCtx) -> String {
        [CLIENT_ID_KEY, CLIENT_SECRET_KEY, REFRESH_KEY]
            .into_iter()
            .filter(|k| ctx.secret(k).is_none())
            .collect::<Vec<_>>()
            .join(", ")
    }

    /// A valid access token; `None` when Netatmo revoked the connection (the
    /// `refresh_token` is then forgotten, the person connects again).
    pub(crate) async fn access(&mut self, ctx: &DriverCtx) -> anyhow::Result<Option<String>> {
        if self.unsaved {
            self.save(ctx).await;
        }
        if let Some((token, ends)) = &self.access
            && Instant::now() < *ends
        {
            return Ok(Some(token.clone()));
        }
        match renew(&self.client_id, &self.client_secret, &self.refresh).await? {
            Outcome::Renewed {
                access,
                refresh,
                lifetime,
            } => {
                if !refresh.is_empty() && refresh != self.refresh {
                    self.refresh = refresh;
                    self.unsaved = true;
                    self.save(ctx).await;
                }
                self.access = Some((
                    access.clone(),
                    Instant::now() + lifetime.saturating_sub(MARGIN),
                ));
                Ok(Some(access))
            }
            Outcome::Revoked(why) => {
                tracing::warn!(instance = %ctx.instance(), reason = %why, "netatmo refresh_token rejected (invalid_grant): forgetting it");
                ctx.forget_secret(REFRESH_KEY).await?;
                self.access = None;
                Ok(None)
            }
        }
    }

    async fn save(&mut self, ctx: &DriverCtx) {
        match ctx.store_secret(REFRESH_KEY, &self.refresh).await {
            Ok(()) => self.unsaved = false,
            Err(e) => {
                tracing::warn!(instance = %ctx.instance(), error = %e, "netatmo: the new refresh_token is not in the vault yet; will try again");
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_invalid_grant_kills_the_refresh_token() {
        let grant = br#"{"error":"invalid_grant"}"#;
        assert!(is_invalid_grant(StatusCode::BAD_REQUEST, grant));
        assert!(!is_invalid_grant(StatusCode::FORBIDDEN, grant));
        assert!(!is_invalid_grant(
            StatusCode::BAD_REQUEST,
            br#"{"error":"invalid_client"}"#
        ));
        assert!(!is_invalid_grant(StatusCode::UNAUTHORIZED, b"not json"));
    }

    #[test]
    fn a_damaged_secret_is_refused_before_any_call() {
        for bad in ["", "a b", "a;b", "a$b", "a\"b", "a`b", &"x".repeat(300)] {
            assert!(looks_corrupted(bad), "{bad:?}");
        }
        assert!(!looks_corrupted("5e7a1b2c3d|4f5e6d7c8b9a0"));
    }

    #[tokio::test]
    async fn renewal_refuses_a_damaged_secret_without_network() {
        let err = renew("id", "secret", "a b").await.unwrap_err();
        assert!(err.to_string().contains("nothing was erased"));
    }

    #[test]
    fn forms_are_encoded() {
        assert_eq!(
            form_encode(&[("a", "x y"), ("b", "1|2")]),
            "a=x%20y&b=1%7C2"
        );
    }

    #[test]
    fn the_answer_to_a_renewal_has_a_default_lifetime() {
        let pair: TokenPair =
            serde_json::from_str(r#"{"access_token":"a","refresh_token":"r"}"#).unwrap();
        assert_eq!(pair.expires_in, 10_800);
    }
}
