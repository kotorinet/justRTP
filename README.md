# JustRTP

<p align="center">
<img src="https://i.imgur.com/oj01YK7.png" alt="JustRTP Logo" width="1000"/>
</p>

<p align="center">
<strong>Random teleport plugin for Paper, Folia, and proxy networks with an arena-style zone system</strong>
</p>

<p align="center">
<img src="https://img.shields.io/badge/Version-3.6.1-brightgreen?style=for-the-badge" alt="Version" />
<img src="https://img.shields.io/badge/API-1.21+-blue?style=for-the-badge" alt="API Version" />
<img src="https://img.shields.io/badge/Java-21+-orange?style=for-the-badge" alt="Java" />
<img src="https://img.shields.io/badge/Folia-Supported-purple?style=for-the-badge" alt="Folia" />
</p>

<p align="center">
<a href="https://discord.gg/HRjcmEYXNy">
<img src="https://img.shields.io/discord/1389677354753720352?color=5865F2&label=Discord&logo=discord&logoColor=white&style=for-the-badge" alt="Discord" />
</a>
</p>

---

## Overview

JustRTP is a random teleport plugin for Paper, Folia, and Velocity/BungeeCord networks. Supports Minecraft 1.21 through 26.2.

### Features

- Per-world cooldowns, radius, cost, and biome rules
- Six landing shapes (round, square, ring, cross, star, spread), global or per world
- Smart location cache that sizes itself per world from player count, live demand, TPS, and free memory, and learns good spots from real teleports
- Air RTP: sky drop with an animated parachute and glide-to-land (command, always mode, per-zone, and cross-server)
- Cross-server teleport via MySQL (Velocity/BungeeCord)
- VOID world type for skyblock/oneblock/void arenas
- RTP zones with countdown holograms, group teleports, an in-game browser GUI (`/rtpzone gui`), and optional air drops
- Hologram backends: FancyHolograms, AxoHologram, PacketEvents, or Display Entities (auto-selected)
- In-game admin dashboard (`/rtp dashboard`) to enable, disable, and tune modules live
- Performance panel (`/rtp performance`) with live metrics and a configurable search benchmark
- World pre-generation (`/rtp pregen`) with square/circle/ring shapes and adaptive/steady pacing, that skips already-generated chunks, harvests safe spots into the cache, and shows a live colour-coded progress map and run history
- RTP GUI with cross-server support, auto-open, and custom items
- Matchmaking queue for PvP duels (1v1, 2v2, team RTP)
- Jump RTP, first-join RTP, respawn RTP, spawn-world redirect
- Near-player and near-claim RTP (SimpleClaim, GriefPrevention, Lands, WorldGuard)
- Custom named locations with radius dispersal
- Vault economy with radius-based pricing and automatic refund on failure
- PlaceholderAPI integration
- MiniMessage formatting, per-world titles, particles, sounds
- FastStats + bStats metrics

---

## Installation

### Quick Start

1. Download `JustRTP.jar` from releases
2. Place in your server's `plugins` folder
3. Restart the server to generate configuration files
4. Configure `config.yml` to your needs
5. Reload with `/rtp reload`

### Dependencies

**Required:**
- Java 21+
- Paper 1.21+, Folia 1.21+, or a Folia fork such as CanvasMC (tested up to 26.2)

**Optional:**
- Vault - economy and permission groups
- PlaceholderAPI - placeholder expansion
- WorldGuard / GriefPrevention / Lands / SimpleClaim - region and claim protection
- FancyHolograms - persistent, editable zone holograms
- AxoHologram - display-based zone holograms
- PacketEvents - packet-based holograms
- MySQL - cross-server teleportation
- Redis - shared cache across proxy network

---

## Documentation

For full documentation, including advanced configuration, permissions, and API usage, please visit our [Wiki](https://deltura.net/wiki/justrtp).

---

## License & Credits

**JustRTP** is developed and maintained with care by **kotori**.

This plugin is open-source software, licensed under the CC BY-NC-SA 4.0 License
