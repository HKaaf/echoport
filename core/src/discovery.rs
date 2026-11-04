//! mDNS / DNS-SD: servers announce themselves, players list what they find.

use anyhow::Result;
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use serde::Serialize;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use crate::protocol::{Source, PROTO_VERSION, SERVICE_TYPE};

pub struct Advertiser {
    daemon: ServiceDaemon,
}

impl Advertiser {
    pub fn start(id: &str, name: &str, port: u16, source: Source, pin_required: bool) -> Result<Self> {
        let daemon = ServiceDaemon::new()?;
        let src = match source {
            Source::Mic => "mic",
            Source::System => "system",
        };
        let ver = PROTO_VERSION.to_string();
        let props = [
            ("id", id),
            ("name", name),
            ("ver", ver.as_str()),
            ("src", src),
            ("pin", if pin_required { "1" } else { "0" }),
        ];
        let host = format!("echoport-{}.local.", &id[..8.min(id.len())]);
        let info = ServiceInfo::new(SERVICE_TYPE, id, &host, "", port, &props[..])?.enable_addr_auto();
        daemon.register(info)?;
        Ok(Advertiser { daemon })
    }
}

impl Drop for Advertiser {
    fn drop(&mut self) {
        let _ = self.daemon.shutdown();
    }
}

#[derive(Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct DiscoveredServer {
    pub id: String,
    pub name: String,
    pub addrs: Vec<String>,
    pub port: u16,
    pub source: String,
    pub pin_required: bool,
}

pub struct Browser {
    daemon: ServiceDaemon,
    found: Arc<Mutex<HashMap<String, DiscoveredServer>>>,
}

impl Browser {
    /// Starts browsing; servers whose id is `own_id` are skipped.
    pub fn start(own_id: String) -> Result<Self> {
        let daemon = ServiceDaemon::new()?;
        let rx = daemon.browse(SERVICE_TYPE)?;
        let found: Arc<Mutex<HashMap<String, DiscoveredServer>>> = Default::default();
        let map = found.clone();
        std::thread::Builder::new().name("echoport-browse".into()).spawn(move || {
            while let Ok(event) = rx.recv() {
                match event {
                    ServiceEvent::ServiceResolved(info) => {
                        let prop = |k: &str| info.get_property_val_str(k).unwrap_or_default().to_string();
                        let id = prop("id");
                        if id.is_empty() || id == own_id {
                            continue;
                        }
                        let mut addrs: Vec<String> = info
                            .get_addresses()
                            .iter()
                            .filter(|a| a.is_ipv4())
                            .map(|a| a.to_string())
                            .collect();
                        addrs.sort();
                        let server = DiscoveredServer {
                            name: prop("name"),
                            source: prop("src"),
                            pin_required: prop("pin") == "1",
                            port: info.get_port(),
                            addrs,
                            id,
                        };
                        map.lock().unwrap().insert(info.get_fullname().to_string(), server);
                    }
                    ServiceEvent::ServiceRemoved(_, fullname) => {
                        map.lock().unwrap().remove(&fullname);
                    }
                    _ => {}
                }
            }
        })?;
        Ok(Browser { daemon, found })
    }

    pub fn servers(&self) -> Vec<DiscoveredServer> {
        let mut list: Vec<_> = self.found.lock().unwrap().values().cloned().collect();
        list.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
        list
    }
}

impl Drop for Browser {
    fn drop(&mut self) {
        let _ = self.daemon.shutdown();
    }
}
