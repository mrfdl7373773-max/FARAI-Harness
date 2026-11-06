<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/banner-dark.png">
    <img src="docs/assets/banner-light.png" width="640" alt="FARAI Agent">
  </picture>
</p>

<h3 align="center">The open source AI coding agent that lives on your Android phone.<br>Real Linux, long-term memory, and runs that keep going in the background, all from your pocket.</h3>

<p align="center">
  <a href="https://github.com/mrfdl7373773-max/FARAI-Harness/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/mrfdl7373773-max/FARAI-Harness?style=flat-square"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
  <a href="LICENSE"><img alt="License MIT" src="https://img.shields.io/github/license/mrfdl7373773-max/FARAI-Harness?style=flat-square"></a>
  <a href="https://github.com/mrfdl7373773-max/FARAI-Harness/releases"><img alt="Downloads" src="https://img.shields.io/github/downloads/mrfdl7373773-max/FARAI-Harness/total?style=flat-square"></a>
</p>

<p align="center">
  <a href="https://github.com/mrfdl7373773-max/FARAI-Harness/releases/latest">Download</a> ·
  <a href="#quick-start">Quick start</a> ·
  <a href="docs/guide.md">Guide</a> ·
  <a href="https://github.com/mrfdl7373773-max/FARAI-Harness/releases">Changelog</a> ·
  <a href="#english">English</a> ·
  <a href="#bahasa-indonesia">Bahasa Indonesia</a>
</p>

---

## English

FARAI turns your phone into a place where real software gets built. It reads and edits your files, runs commands in a full Linux environment, searches the web, and keeps working in the background while you do something else. Everything the model is told lives in plain Markdown files you can open and change.

### Install

1. Download **FARAI-2.0.0.apk** from the [latest release](https://github.com/mrfdl7373773-max/FARAI-Harness/releases/latest).
2. Open it and allow installs from your browser or file manager.
3. Add your provider: any OpenAI-compatible API (OpenRouter, Groq, DeepSeek, a local Ollama or LM Studio server…), your API key, and a model.

> [!NOTE]
> 2.0 is the first public release. It is an early preview, so expect rough edges and please report what you find.

### Features

- **Real Linux, no root.** Ubuntu 24.04, Ubuntu 26.04 or Alpine through PRoot. Python, Node, git, compilers and package managers work as on a computer, and the agent installs what a task needs.
- **Agents that work like a team.** Built-in `build`, `plan` and `verify` agents, your own agents as Markdown files, parallel subagents, and `/goal` to keep going until an objective is met.
- **Keeps working in the background.** Close the app and a notification tells you when it is done. If the internet drops, FARAI pauses and continues from where it stopped once you are back online.
- **Long-term memory.** Remembers you and your projects across sessions, can search every past conversation, and tidies its own memory. Stored on your device; every change can be undone.
- **Connected.** MCP servers (local and remote, with OAuth), built-in web search and page reading, and reusable skills (`SKILL.md`).
- **You stay in control.** Approve once, always, or deny with feedback. Permission rules per agent, `/undo` that restores changed files, and secrets that never go into memory.
- **Every instruction is editable.** Prompts, tool descriptions, agents and permissions are files you can edit, diff, roll back and reset from Settings.
- **Any model.** OpenRouter or any OpenAI-compatible endpoint, with adjustable reasoning effort. The app speaks English and Bahasa Indonesia.

### Quick start

1. Install the APK and add your provider.
2. **Settings → Linux** and install Ubuntu or Alpine.
3. Choose a workspace folder and tell FARAI what to build.

### Commands

Type `/` in the chat for autocomplete, or `/help` for all 44 commands.

| Command | What it does |
|---|---|
| `/agent` | Switch between build, plan, verify or your own agents |
| `/goal` | Keep working until a reviewer confirms the objective is met |
| `/model`, `/reasoning` | Change the model and its reasoning effort |
| `/memory`, `/remember`, `/recall` | See and manage what FARAI remembers |
| `/mcp` | Add, connect and sign in to MCP servers |
| `/skills` | Create, import and use skills |
| `/linux`, `/pip` | Manage Linux and run commands directly |
| `/undo` | Remove the last message and restore the files it changed |
| `/init` | Study the project and write `AGENTS.md` |

The full list, configuration and internals are in the [guide](docs/guide.md).

### Build from source

Requires JDK 17 and the Android SDK (platform 34).

```bash
git clone https://github.com/mrfdl7373773-max/FARAI-Harness.git
cd FARAI-Harness
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
```

### Project layout

```
app/src/main/java/dev/harness/
  core/        engine, agents, permissions, memory, mcp, search, web, linux, background runs
  tools/       read, write, edit, bash, web_search, web_fetch, task, skill and more
  net/         OpenAI-compatible client, streaming, offline pause and resume
  ui/          Jetpack Compose screens and components
app/src/main/assets/defaults/prompts/   every instruction the model receives
```

### Roadmap

- Testing on more devices and Android versions
- Signed release builds
- More languages for the app

### Contributing

Issues and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before you open one.

### License

[MIT](LICENSE). Third-party notices are in [THIRD_PARTY.md](THIRD_PARTY.md).

---

## Bahasa Indonesia

FARAI mengubah HP kamu jadi tempat membuat software sungguhan. FARAI bisa membaca dan mengedit file, menjalankan perintah di lingkungan Linux lengkap, mencari di web, dan tetap bekerja di latar belakang saat kamu mengerjakan hal lain. Semua instruksi yang diterima model ada di file Markdown biasa yang bisa kamu buka dan ubah.

### Pemasangan

1. Unduh **FARAI-2.0.0.apk** dari [rilis terbaru](https://github.com/mrfdl7373773-max/FARAI-Harness/releases/latest).
2. Buka file-nya dan izinkan pemasangan dari browser atau pengelola file.
3. Tambahkan provider: API apa pun yang kompatibel dengan OpenAI (OpenRouter, Groq, DeepSeek, server Ollama atau LM Studio lokal…), API key, dan model.

> [!NOTE]
> 2.0 adalah rilis publik pertama. Ini masih pratinjau awal, jadi mungkin ada yang belum mulus. Laporkan kalau kamu menemukan masalah.

### Fitur

- **Linux sungguhan, tanpa root.** Ubuntu 24.04, Ubuntu 26.04, atau Alpine lewat PRoot. Python, Node, git, compiler, dan package manager jalan seperti di komputer, dan agent memasang sendiri apa yang dibutuhkan tugasnya.
- **Agent yang bekerja seperti tim.** Agent bawaan `build`, `plan`, dan `verify`, agent buatanmu sendiri dalam bentuk file Markdown, subagent paralel, dan `/goal` untuk terus bekerja sampai target tercapai.
- **Tetap jalan di latar belakang.** Tutup aplikasinya, nanti notifikasi memberi tahu kalau sudah selesai. Kalau internet putus, FARAI berhenti sejenak dan lanjut dari titik terakhir begitu internet tersambung lagi.
- **Memori jangka panjang.** Mengingat kamu dan proyekmu antar sesi, bisa mencari di semua percakapan lama, dan merapikan memorinya sendiri. Disimpan di perangkatmu, dan setiap perubahan bisa dibatalkan.
- **Terhubung.** Server MCP (lokal dan remote, dengan OAuth), pencarian web dan pembaca halaman bawaan, serta skill yang bisa dipakai ulang (`SKILL.md`).
- **Kamu tetap pegang kendali.** Izinkan sekali, selalu, atau tolak dengan masukan. Aturan izin per agent, `/undo` yang memulihkan file yang diubah, dan rahasia yang tidak pernah masuk ke memori.
- **Semua instruksi bisa diedit.** Prompt, deskripsi tool, agent, dan aturan izin adalah file yang bisa kamu edit, bandingkan, kembalikan, dan reset dari Settings.
- **Model apa saja.** OpenRouter atau endpoint apa pun yang kompatibel dengan OpenAI, dengan reasoning effort yang bisa diatur. Aplikasi tersedia dalam bahasa Inggris dan Indonesia.

### Mulai cepat

1. Pasang APK dan tambahkan provider.
2. Buka **Settings → Linux** lalu pasang Ubuntu atau Alpine.
3. Pilih folder workspace dan beri tahu FARAI apa yang mau dibuat.

### Perintah

Ketik `/` di chat untuk saran otomatis, atau `/help` untuk melihat 44 perintah.

| Perintah | Fungsi |
|---|---|
| `/agent` | Ganti ke agent build, plan, verify, atau agent buatanmu |
| `/goal` | Terus bekerja sampai peninjau memastikan target tercapai |
| `/model`, `/reasoning` | Ganti model dan reasoning effort-nya |
| `/memory`, `/remember`, `/recall` | Lihat dan atur apa yang diingat FARAI |
| `/mcp` | Tambah, sambungkan, dan login ke server MCP |
| `/skills` | Buat, impor, dan pakai skill |
| `/linux`, `/pip` | Atur Linux dan jalankan perintah langsung |
| `/undo` | Hapus pesan terakhir dan pulihkan file yang diubahnya |
| `/init` | Pelajari proyek dan tulis `AGENTS.md` |

Daftar lengkap, konfigurasi, dan cara kerja di dalamnya ada di [panduan](docs/guide.md).

### Build dari source

Butuh JDK 17 dan Android SDK (platform 34). Perintahnya sama seperti di bagian English di atas.

### Roadmap

- Pengujian di lebih banyak perangkat dan versi Android
- Build rilis yang ditandatangani (signed)
- Lebih banyak bahasa untuk aplikasi

### Kontribusi

Issue dan pull request sangat diterima. Baca [CONTRIBUTING.md](CONTRIBUTING.md) sebelum membuatnya.

### Lisensi

[MIT](LICENSE). Pemberitahuan pihak ketiga ada di [THIRD_PARTY.md](THIRD_PARTY.md).

---

<p align="center">Made by Farrel · Dibuat oleh Farrel</p>
