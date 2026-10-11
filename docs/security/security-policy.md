# Draft for the repository root `SECURITY.md` (owner: security-engineer)

Status: draft, 2026-10-08 (SP-4, task SEC-4C). The orchestrator copies everything **below the line** to `/SECURITY.md` on `master`.

Notes for the orchestrator and the PO (do not copy):
- **PO action:** enable GitHub private vulnerability reporting (repo Settings › Code security). Until it is on, the text below tells reporters to use a private contact through the maintainer's GitHub profile. When it is on, the text still works; no edit is needed.
- **Mongolian text: needs native review.** It uses glossary terms where they exist: «байршил», «газар», «уулзвар», «газрын зураг», «Офлайн газрын зураг» (the downloaded pack), «хувилбар», «OpenStreetMap». A GPX recording is called «GPX файл», not «маршрут», because «маршрут» is the computed route. These words are **not** in the glossary yet and are provisional until the BA adds them: «аюулгүй байдал» (security), «эмзэг байдал» (vulnerability), «нууцаар мэдээлэх» (report privately), «төслийг хариуцагч» (maintainer), «нийтэд нээлттэй issue» (public issue), «туршилтын build» (test build), «өгөгдөл боловсруулалт» (data pipeline).
- No contact email, host or domain is included on purpose.

---

# Security policy · Аюулгүй байдлын бодлого

## English

### Supported versions

This project is **pre-release**. Only the **latest commit of the default branch** gets security fixes. There are no supported older versions, and test builds (including the demo APK) are not supported releases.

### Report a vulnerability privately

**Please do not open a public issue, discussion or pull request for a security problem.** This repository is public.

1. Use **"Report a vulnerability"** on the repository's **Security** tab (GitHub private vulnerability reporting).
2. If that button is not available yet, contact the maintainer privately through the contact options on their **GitHub profile**. Only say that you have a security report; send the details after we reply with a private channel.

### What to include

- The affected part: backend / gateway, data pipeline or offline packs, Android app, web demo, or the repository and its workflows.
- Version, build or commit.
- Steps to reproduce, and what an attacker could do.
- Any proof of concept, kept to the minimum needed to show the problem.
- **Do not include real personal data:** no home or work locations, real GPX tracks or logs with your position. Use a public place or a test coordinate.

Please test only against your **own local copy** (for example the Docker stack in `backend/`, an emulator or your own build). Do not test servers or accounts you do not own, and do not access other people's data.

### What happens next

- We **acknowledge your report within 7 days**.
- We confirm the problem, rate its severity and tell you the plan.
- We agree a disclosure date with you, publish an advisory after the fix, and credit you if you wish.

### Scope

- **In scope:** the code, configuration, build and workflow files in this repository.
- **Out of scope:**
  - **OpenStreetMap data errors** (wrong street name, missing road, wrong one-way). Please report or fix them on openstreetmap.org. Ordinary bugs go to the bug issue form.
  - Third-party services and software we use (report to their maintainers), unless our configuration causes the problem.
  - Denial-of-service tests against any running server, social engineering, and physical attacks.

## Монгол (needs native review)

### Дэмжигдэх хувилбар

Энэ төсөл хараахан нийтэд гараагүй байна. Аюулгүй байдлын засвар зөвхөн үндсэн салбарын **хамгийн сүүлийн хувилбарт** хийгдэнэ. Хуучин хувилбар болон туршилтын build (туршилтын APK орно) дэмжигдэхгүй.

### Эмзэг байдлыг нууцаар мэдээлэх

**Аюулгүй байдлын асуудлыг нийтэд нээлттэй issue, discussion эсвэл pull request-ээр бүү мэдээлээрэй.** Энэ репозитори нийтэд нээлттэй.

1. Репозиторийн **Security** хэсэгт байгаа **"Report a vulnerability"** товчийг ашиглана уу.
2. Хэрэв энэ товч хараахан байхгүй бол төслийг хариуцагчтай түүний **GitHub профайл** дээрх холбоо барих хаягаар нууцаар холбогдоно уу. Эхний мессежид зөвхөн аюулгүй байдлын мэдээлэл байгаа гэдгээ бичээд, дэлгэрэнгүйг бид нууц сувгаар хариулсны дараа илгээнэ үү.

### Юу бичих вэ

- Аль хэсэгт хамаарах: backend / gateway, өгөгдөл боловсруулалт эсвэл «Офлайн газрын зураг», Android апп, вэб demo, репозитори.
- Хувилбар, build эсвэл commit.
- Давтан гаргах алхмууд, халдагч юу хийж чадах.
- **Өөрийн бодит хувийн мэдээллийг бүү оруулаарай:** гэр, ажлын байршил, өөрийн бодит GPX файл, байршилтай лог. Нийтийн газар, уулзвар эсвэл туршилтын координат ашиглана уу.

Зөвхөн **өөрийн локал хуулбар** дээр туршина уу (`backend/` дахь Docker орчин, эмулятор эсвэл өөрийн build). Өөрт хамааралгүй сервер, бүртгэлийг бүү туршиж, бусдын мэдээлэлд бүү хандаарай.

### Дараа нь юу болох вэ

- Таны мэдээллийг **7 хоногийн дотор** хүлээн авснаа мэдэгдэнэ.
- Асуудлыг шалгаж, ноцтой байдлыг тогтоож, төлөвлөгөөгөө танд хэлнэ.
- Засварын дараа нийтлэх огноог тантай тохиролцоно. Хүсвэл таны нэрийг дурдана.

### Хамрах хүрээ

- **Хамаарна:** энэ репозиторийн код, тохиргоо, build болон workflow файлууд.
- **Хамаарахгүй:** **OpenStreetMap-ийн газрын зургийн мэдээллийн алдаа** (гудамжны нэр буруу, зам байхгүй, нэг чиглэлийн зам буруу). Тэдгээрийг openstreetmap.org дээр мэдээлж эсвэл засна уу. Энгийн алдааг алдаа мэдээлэх маягтаар илгээнэ үү. Мөн гуравдагч талын үйлчилгээ, ажиллаж буй сервер рүү чиглэсэн DoS туршилт хамаарахгүй.
