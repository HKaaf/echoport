//! Lists this device's IPv4 addresses, one per network adapter, so the user can see
//! every way a player can reach it (Wi-Fi, Ethernet, USB tethering, ...).

use serde::Serialize;
use std::net::IpAddr;

#[derive(Serialize, Debug, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Interface {
    pub name: String,
    pub ip: String,
    /// `wifi`, `ethernet`, `usb`, `virtual` or `other`.
    pub kind: String,
}

fn classify(name: &str) -> &'static str {
    let n = name.to_lowercase();
    if n.contains("ndis") || n.contains("usb") || n.contains("tether") {
        "usb"
    } else if n.contains("wi-fi") || n.contains("wifi") || n.contains("wlan") || n.contains("wireless") {
        "wifi"
    } else if n.contains("vethernet") || n.contains("virtual") || n.contains("vmware") || n.contains("hyper-v") || n.contains("vpn") {
        "virtual"
    } else if n.contains("ethernet") || n.starts_with("eth") || n.starts_with("en") {
        "ethernet"
    } else {
        "other"
    }
}

pub fn ipv4_interfaces() -> Vec<Interface> {
    let mut list: Vec<Interface> = if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter(|i| !i.is_loopback())
        .filter_map(|i| match i.ip() {
            IpAddr::V4(ip) if !ip.is_link_local() => Some(Interface {
                kind: classify(&i.name).into(),
                name: i.name,
                ip: ip.to_string(),
            }),
            _ => None,
        })
        .collect();
    let rank = |k: &str| ["wifi", "ethernet", "usb", "other", "virtual"].iter().position(|x| *x == k).unwrap_or(9);
    list.sort_by_key(|i| rank(&i.kind));
    list.dedup_by(|a, b| a.ip == b.ip);
    list
}

#[cfg(test)]
mod tests {
    use super::classify;

    #[test]
    fn adapter_names() {
        assert_eq!(classify("Wi-Fi"), "wifi");
        assert_eq!(classify("Ethernet 2"), "ethernet");
        assert_eq!(classify("Remote NDIS based Internet Sharing Device"), "usb");
        assert_eq!(classify("vEthernet (WSL)"), "virtual");
    }
}
