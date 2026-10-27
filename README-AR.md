# حرّاس الجزيرة — نسخة الحسابات (v1.13)

- تسجيل دخول بحساب جوجل (Credential Manager + Firebase Auth)
- اسم حساب + رقم ID من 6 أرقام (100000–999999) يُحجز داخل Transaction في Firestore فلا يتكرر أبدًا
- نسختان: official (com.hurras.island) و dev (com.hurras.island.dev) — نفس كود اللعبة، والمطوّر فيه موارد غير محدودة
- تقدم اللعبة يُحفظ على الجهاز لكل حساب على حدة (الحفظ السحابي خطوة قادمة)
- ملفات مهمة: firestore.rules (قواعد الأمان) — signing/cert.pem (شهادة التوقيع العامة) — app/google-services.json
