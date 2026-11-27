//! Off-peak hours by the clock: a device that says the tariff period in
//! force, `HP` (heures pleines) or `HC` (heures creuses), from the hours of
//! the house's contract, for a home whose meter does not say it (no Linky
//! reader). Like Home Assistant's « times of the day » helper.
//!
//! `[energy.tariff] period` names its point (`<instance>:periode/period`):
//! each hour is priced at its period, and a driven load tops up in « HC ».
//! The period is worked out from the clock every minute, so it is right
//! again as soon as Moli starts, whatever happened while it was stopped.
//!
//! ```toml
//! [[driver]]
//! id = "tarif"
//! kind = "offpeak"
//! [driver.options]
//! offpeak = ["22:00-06:00"]   # or two stretches: ["01:30-07:30", "12:30-14:30"]
//! ```

use std::time::Duration;

use anyhow::ensure;
use jiff::tz::TimeZone;
use moli_core::{Access, Device, Kind, PointSpec, Semantic, Value};
use moli_runtime::{BoxFuture, Driver, DriverCtx};
use serde::Deserialize;

/// The device (`<instance>:periode`) and its point.
pub const DEVICE: &str = "periode";
pub const KEY: &str = "period";
pub const PEAK: &str = "HP";
pub const OFF_PEAK: &str = "HC";

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Config {
    /// Off-peak stretches, local time (`"22:00-06:00"`); `end` before
    /// `start` crosses midnight.
    pub offpeak: Vec<String>,
}

/// A stretch in minutes after local midnight.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Stretch {
    start: u32,
    end: u32,
}

impl Stretch {
    fn parse(text: &str) -> anyhow::Result<Self> {
        let minutes = |hm: &str| -> Option<u32> {
            let (h, m) = hm.trim().split_once(':')?;
            let (h, m): (u32, u32) = (h.trim().parse().ok()?, m.trim().parse().ok()?);
            (h < 24 && m < 60).then_some(h * 60 + m)
        };
        let parsed = text
            .split_once('-')
            .and_then(|(a, b)| Some((minutes(a)?, minutes(b)?)));
        match parsed {
            Some((start, end)) if start != end => Ok(Self { start, end }),
            _ => anyhow::bail!("{text:?}: expected \"HH:MM-HH:MM\""),
        }
    }

    fn contains(self, minute: u32) -> bool {
        if self.start < self.end {
            (self.start..self.end).contains(&minute)
        } else {
            minute >= self.start || minute < self.end
        }
    }
}

/// The period at `minute` after local midnight.
fn period(stretches: &[Stretch], minute: u32) -> &'static str {
    if stretches.iter().any(|s| s.contains(minute)) {
        OFF_PEAK
    } else {
        PEAK
    }
}

#[derive(Debug)]
pub struct OffPeak {
    stretches: Vec<Stretch>,
    zone: TimeZone,
}

impl OffPeak {
    pub fn new(config: Config) -> anyhow::Result<Self> {
        let Config { offpeak } = config;
        ensure!(
            !offpeak.is_empty(),
            "offpeak: at least one stretch (\"22:00-06:00\")"
        );
        let stretches = offpeak
            .iter()
            .map(|s| Stretch::parse(s))
            .collect::<anyhow::Result<Vec<_>>>()?;
        // The house's clock: the zone Moli runs in (`TZ`, set by the app).
        Ok(Self {
            stretches,
            zone: TimeZone::system(),
        })
    }

    fn now(&self) -> (u32, u64) {
        let now = jiff::Timestamp::now().to_zoned(self.zone.clone());
        let minute =
            u32::try_from(i32::from(now.hour()) * 60 + i32::from(now.minute())).unwrap_or(0);
        let left = 60 - u64::try_from(now.second()).unwrap_or(0);
        (minute, left)
    }
}

impl Driver for OffPeak {
    fn kind(&self) -> &'static str {
        "offpeak"
    }

    /// Worked out from the clock: a mirror house works it out too.
    fn stays_inside(&self) -> bool {
        true
    }

    fn run<'a>(&'a self, ctx: &'a mut DriverCtx) -> BoxFuture<'a, anyhow::Result<()>> {
        Box::pin(async move {
            let id = ctx.device_id(DEVICE);
            ctx.upsert_device(Device {
                id: id.clone(),
                instance: ctx.instance().clone(),
                native_name: moli_i18n::tr!("pilotes.offpeak.periode").into(),
                manufacturer: Some("Moli".into()),
                model: Some(moli_i18n::tr!("pilotes.offpeak.modele").into()),
                description: None,
                native_room: None,
                members: Vec::new(),
                points: vec![PointSpec {
                    key: KEY.into(),
                    label: moli_i18n::tr!("pilotes.offpeak.point").into(),
                    kind: Kind::Enum {
                        values: vec![PEAK.into(), OFF_PEAK.into()],
                    },
                    access: Access {
                        read: true,
                        write: false,
                    },
                    unit: None,
                    semantic: Semantic::Other,
                }],
            });
            ctx.set_availability(&id, true);
            ctx.ready();
            let mut last = None;
            loop {
                let (minute, left) = self.now();
                let now = period(&self.stretches, minute);
                if last != Some(now) {
                    ctx.set_state(&id, KEY, Value::Text(now.into()));
                    last = Some(now);
                }
                // Read-only: an order is refused, the clock is not stopped by
                // it. `None`: Moli is shutting down.
                tokio::select! {
                    command = ctx.next_command() => match command {
                        Some(command) => command.reply(Err("read only".into())),
                        None => return Ok(()),
                    },
                    () = tokio::time::sleep(Duration::from_secs(left.max(1))) => {}
                }
            }
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    use moli_core::{InstanceId, PointId};
    use moli_runtime::{Hub, HubOptions, spawn_driver};
    use tokio_util::sync::CancellationToken;

    fn stretches(list: &[&str]) -> Vec<Stretch> {
        list.iter().map(|s| Stretch::parse(s).unwrap()).collect()
    }

    #[test]
    fn the_period_follows_the_stretches_across_midnight() {
        let night = stretches(&["22:00-06:00"]);
        assert_eq!(period(&night, 21 * 60 + 59), PEAK);
        assert_eq!(period(&night, 22 * 60), OFF_PEAK);
        assert_eq!(period(&night, 0), OFF_PEAK);
        assert_eq!(period(&night, 5 * 60 + 59), OFF_PEAK);
        assert_eq!(period(&night, 6 * 60), PEAK);
        let two = stretches(&["01:30-07:30", "12:30-14:30"]);
        assert_eq!(period(&two, 13 * 60), OFF_PEAK);
        assert_eq!(period(&two, 15 * 60), PEAK);
        assert_eq!(period(&two, 2 * 60), OFF_PEAK);
    }

    #[test]
    fn bad_hours_are_refused() {
        let config = |list: &[&str]| Config {
            offpeak: list.iter().map(|s| (*s).to_owned()).collect(),
        };
        OffPeak::new(config(&["22:00-06:00"])).unwrap();
        OffPeak::new(config(&[" 12:30 - 14:30 "])).unwrap();
        for bad in [
            &[][..],
            &["22h-6h"],
            &["25:00-06:00"],
            &["22:00-22:00"],
            &["22:00"],
        ] {
            assert!(OffPeak::new(config(bad)).is_err(), "{bad:?}");
        }
        assert!(toml::from_str::<Config>("offpeak = [\"22:00-06:00\"]\nhours = 3").is_err());
    }

    #[tokio::test]
    async fn the_device_says_the_period_now_and_refuses_orders() {
        let hub = Hub::new(HubOptions::default()).unwrap();
        let cancel = CancellationToken::new();
        // Off-peak all day but one minute: whatever the clock, almost surely HC.
        let all_day = Config {
            offpeak: vec!["00:00-23:59".into()],
        };
        spawn_driver(
            &hub,
            InstanceId::from("tarif"),
            Arc::new(OffPeak::new(all_day).unwrap()),
            cancel.clone(),
        );
        let point = PointId::from("tarif:periode/period".to_owned());
        for _ in 0..200 {
            if hub.state(&point).is_some() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        let value = hub.state(&point).unwrap().value;
        assert!(
            value == Value::Text(OFF_PEAK.into()) || value == Value::Text(PEAK.into()),
            "{value:?}"
        );
        let refused = hub
            .command(
                &point,
                Value::Text(PEAK.into()),
                moli_core::Origin::Ui,
                None,
            )
            .await;
        assert!(refused.is_err(), "the clock says it, not an order");
        cancel.cancel();
    }
}
