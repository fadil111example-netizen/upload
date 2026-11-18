# Upload GitHub (Android)

Aplikasi Android (Kotlin + Jetpack Compose) yang melakukan **hal yang sama persis** dengan skrip
`upload.sh` v2.0: mengunggah satu atau beberapa folder project / arsip ke GitHub lewat REST API
(membuat repo, update repo tanpa force push, skip file besar, pindai rahasia, retry push, verifikasi commit).

Isi skrip aslinya tetap dibundel di dalam APK (`app/src/main/assets/upload.sh`) dan bisa dibaca/di-salin
dari aplikasi (ikon **i** di kanan atas).

---

## 1. Fitur

| Fitur di `upload.sh` | Padanan di aplikasi |
| --- | --- |
| `--token` simpan token + `chmod 600` | Dialog **Token GitHub** (verifikasi ke `GET /user`), disimpan di penyimpanan privat app |
| Cek repo `GET /repos/...` | `GitHubClient.getRepo()` (200 / 404 / 401 / 403 ditangani) |
| Buat repo `POST /user/repos` (private/public) | `GitHubClient.createRepo()` |
| Update deskripsi `PATCH /repos/...` | `GitHubClient.updateDescription()` (pakai OkHttp, karena `HttpURLConnection` Android tidak bisa `PATCH`) |
| `git init / remote / fetch / reset / add -A / commit` | **JGit** (`GitEngine.kt`) |
| Update repo lama tanpa force push | `fetch` + `reset --mixed` ke `origin/<branch>` lalu commit di atasnya |
| Abaikan `node_modules`, `__pycache__`, `.gradle` bila tak ada `.gitignore` | ditulis ke `.git/info/exclude` **sebelum** `git add` (`Scanner.ignorePatterns`) |
| Lewati file > 100 MB (`find -size +100M`) | `Scanner` (ambang 100 MB) + masuk `info/exclude` |
| Pindai rahasia (`ghp_`, `AKIA`, `sk_live_`, `BEGIN PRIVATE KEY`, dst.) | `Scanner.SECRET_PATTERNS` |
| Cek `.env` / `id_rsa` / `*.pem` hanya untuk repo public | `Scanner(checkCredentialNames = isPublic)` |
| Pertanyaan `confirm()` untuk file sensitif | Dialog **File sensitif terdeteksi** (Lewati / Tetap kirim / Batal) |
| Push 3x retry + `push.log` | `GitEngine.push()` (retry 3x, peta pesan error) |
| Verifikasi commit (`ls-remote`) | `GitEngine.verifyRemote()` |
| Multi project (`upload.sh A B C`) | Daftar project, upload berurutan + ringkasan |
| `--name`, `--branch`, `--msg`, `--desc`, `--dry-run`, `--all`, `--no-delete`, `-y` | Kartu **Opsi upload** |
| `unzip` / `tar` / `7z x` | **commons-compress** (`ArchiveExtractor.kt`, ada proteksi path traversal) |
| Folder asli tidak pernah diubah | project **disalin** ke penyimpanan privat app lalu dihapus setelah selesai |

Di Android tidak ada binary `git`, `unzip`, `tar`, atau `7z`, jadi semua pekerjaan itu dilakukan di dalam
proses aplikasi memakai library Java. Folder/arsip asli milik Anda **tidak pernah** diubah.

---

## 2. Cara pakai

1. **Buat token GitHub**
   - Classic: buka <https://github.com/settings/tokens> → *Generate new token (classic)* → centang scope **`repo`**
     (dan **`public_repo`** kalau hanya upload ke repo public).
   - Fine-grained: <https://github.com/settings/personal-access-tokens> → pilih *All repositories* →
     **Repository permissions**: *Contents: Read and write* **dan** *Administration: Read and write*
     (Administration hanya dibutuhkan untuk membuat repo baru).
   - Token dengan kedaluwarsa juga didukung (aplikasi menampilkan tanggal berlakunya).
2. **Buka aplikasi → ikon kanan atas → Token GitHub** → tempel token → **Simpan**.
   Aplikasi langsung mengecek token ke GitHub dan menampilkan nama akun Anda.
3. **Tambahkan project**: tombol **Folder** (project di HP) atau **Arsip** (`.zip`, `.tar`, `.tar.gz`, `.tgz`,
   `.tar.bz2`, `.7z`). Bisa menambahkan lebih dari satu.
4. **Atur opsi bila perlu** (semua opsional):
   - *Repository public* → mati = private (default skrip).
   - *Dry-run* → hanya menghitung file, tidak membuat repo / push.
   - *Ikutkan node_modules / __pycache__ / .gradle* → setara `--all`.
   - *Jangan hapus file di repo lama* → setara `--no-delete`.
   - *Otomatis (tanpa tanya)* → setara `-y`, file sensitif langsung dilewati.
   - *Nama repo kustom* (`--name`, hanya bila 1 project), *Branch* (`--branch`), *Pesan commit* (`--msg`),
     *Deskripsi repo* (`--desc`).
5. **Mulai upload**. Lihat panel **Log** (ikon jam) untuk prosesnya, dan bagian **Hasil** untuk link repo.

Nama repo diambil otomatis dari nama folder project; kalau dari arsip yang berisi satu folder root maka
nama folder itulah yang dipakai (sama seperti logika di skrip), kalau tidak maka nama file arsip.

---

## 3. Build APK lewat GitHub Actions

Workflow ada di `.github/workflows/build.yml`. Yang dibutuhkan hanya push folder ini ke GitHub.

1. Push project ke GitHub (`git push`).
2. Buka tab **Actions** → pilih workflow **Build APK** → **Run workflow** (atau otomatis jalan saat push/PR ke `main`/`master`).
3. Tunggu sampai selesai:
   - **Berhasil** → artifact **`upload-github-debug-apk`** berisi `app-debug.apk` (juga dilampirkan ke
     *Release* bila Anda push tag `v*`).
   - **Gagal** → artifact **`build-logs`** berisi log lengkap. Ini yang bisa Anda kirimkan ke saya.
4. Unduh APK: buka halaman run → bagian **Artifacts** → `upload-github-debug-apk`.

### Kalau build gagal, log yang harus diambil

Pada halaman run yang gagal, unduh artifact **`build-logs`**. Isinya:

| File | Isi |
| --- | --- |
| `build-errors.txt` | Hanya baris error penting (`e: ...`, `Unresolved reference`, `Caused by`, task FAILED) |
| `build-tail.txt` | 120 baris terakhir output Gradle |
| `build-full.log` | Seluruh output build (untuk kasus sulit) |
| `environment.txt` | Versi Java, Gradle, dan daftar paket SDK |

Cara tercepat: kirimkan isi **`build-errors.txt`** + **`build-tail.txt`**; kalau belum cukup, kirim
**`build-full.log`**.

Tambahan otomatis:

- Halaman **Actions** menampilkan ringkasan error di *Job summary*.
- Kalau build gagal karena **Pull Request**, ringkasannya otomatis diposting sebagai komentar di PR tersebut.
- Kalau build gagal karena **push / Run workflow**, log juga di-commit ke cabang
  `ci-logs/log-<nomor-run>` supaya bisa dilihat langsung dari repo (tidak wajib di-download).

---

## 4. Build lokal (opsional)

Butuh Android Studio (Ladybug atau lebih baru) atau JDK 17 + Android SDK + Gradle:

```bash
cd upload
gradle wrapper --gradle-version 8.9   # sekali saja, bila ingin memakai ./gradlew
./gradlew :app:assembleDebug
# hasil: app/build/outputs/apk/debug/app-debug.apk
```

Konfigurasi: `compileSdk`/`targetSdk` 35, `minSdk` 26 (Android 8.0+), JVM 17.
Repositori ini sengaja **tidak** menyertakan `gradlew`/`gradle-wrapper.jar`; CI memakai Gradle 8.9
yang disediakan `gradle/actions/setup-gradle`.

---

## 5. Struktur kode

```
app/src/main/assets/upload.sh                     skrip asli (ditampilkan di aplikasi)
app/src/main/java/com/termux/githubuploader/
  MainActivity.kt                                 pemilih folder/arsip (SAF) + navigasi layar
  UploadViewModel.kt                              state UI, log, dialog, alur upload
  core/Models.kt                                  Target, UploadOptions, UploadOutcome, Reporter
  core/Names.kt                                   sanitize nama repo, nama dasar arsip
  core/TokenStore.kt                              simpan token (setara chmod 600)
  core/GitHubClient.kt                            semua endpoint REST GitHub (OkHttp)
  core/ProjectLoader.kt                           salin folder / ekstrak arsip ke workspace privat
  core/ArchiveExtractor.kt                        zip / tar(+gz,bz2,xz) / 7z
  core/Scanner.kt                                 prune, >100MB, pindai rahasia, info/exclude
  core/GitEngine.kt                               init/remote/ls-remote/fetch/reset/add/commit/push/verify
  core/UploadEngine.kt                            plan() + upload() (padanan process_project())
  ui/…                                            Compose: MainScreen, UploadTab, LogScreen,
                                                  ScriptScreen, HelpScreen, Components, Dialogs, theme
```

---

## 6. Catatan penting

- Token **tidak pernah** ditulis ke log, dan hanya dikirim ke `api.github.com` / `github.com`.
- Saat update repo lama, aplikasi **menyambung ke history remote** sehingga tidak perlu force push
  dan commit lama tetap aman.
- File yang dilewati (sensitif / >100 MB / folder yang di-prune) ditulis ke `.git/info/exclude`
  sebelum proses `git add`, jadi tidak akan pernah ikut ke commit berikutnya.
- Aplikasi memakai `largeHeap` karena JGit memuat objek commit di memori.
