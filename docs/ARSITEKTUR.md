# Arsitektur Molina Studio

Peta ketergantungan antar-paket aplikasi (Molina Terminal dan Molina Cinema). Diagram ini dihasilkan dari kode oleh `scripts/pkg_graph.py --mermaid`, jadi selalu mengikuti kenyataan. Graf bebas siklus: tidak ada paket yang saling bergantung (dijaga CI lewat `--max-cycles 0 --max-cyclic 0`).

```mermaid
flowchart TD
    APP["app: MainActivity, MolinaApp (perakit)"]
    apt["apt"]
    automation["automation"]
    backup["backup"]
    bridge["bridge"]
    cinema["cinema"]
    cmd["cmd"]
    code["code"]
    common["common"]
    download["download"]
    log["log"]
    packages["packages"]
    settings["settings"]
    stream["stream"]
    system["system"]
    terminal["terminal"]
    apt --> packages
    automation --> apt
    automation --> backup
    automation --> cinema
    automation --> code
    automation --> packages
    automation --> stream
    backup --> code
    backup --> terminal
    bridge --> code
    bridge --> common
    cinema --> bridge
    cinema --> code
    cinema --> common
    cinema --> stream
    cinema --> system
    cinema --> terminal
    cmd --> bridge
    cmd --> cinema
    cmd --> code
    cmd --> system
    cmd --> terminal
    code --> common
    download --> automation
    download --> bridge
    download --> cinema
    download --> code
    download --> common
    download --> system
    log --> terminal
    packages --> code
    packages --> terminal
    settings --> apt
    settings --> automation
    settings --> backup
    settings --> bridge
    settings --> cinema
    settings --> code
    settings --> common
    settings --> download
    settings --> log
    settings --> packages
    settings --> stream
    settings --> system
    settings --> terminal
    stream --> common
    stream --> terminal
    system --> common
    system --> terminal
    terminal --> bridge
    terminal --> code
    terminal --> common
    APP --> cmd
    APP --> settings
    classDef base fill:#2D6DB5,stroke:#8FC4EE,color:#fff
    classDef knot stroke:#D33,stroke-width:3px
    class common base
```
