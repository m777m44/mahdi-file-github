# 📱 تطبيق أندرويد — GitHub Uploader

تطبيق أندرويد أصلي (Kotlin) بواجهة عربية RTL احترافية بتصميم Material 3 داكن، يطابق وظائف نسخة الويب.

## ✨ الميزات
- 🔐 **تسجيل دخول رسمي** عبر GitHub OAuth **Device Flow**: يعرض التطبيق رمزاً، تدخله في `github.com/login/device` من أي متصفح، ويكتمل الدخول تلقائياً
- 🔑 بديل سريع: الدخول بـ **Personal Access Token**
- 📜 عرض **كل مستودعاتك** مع بحث فوري وشارة عام 🌍 / خاص 🔒
- ➕ **إنشاء مستودع جديد** (عام/خاص) والانتقال مباشرة للرفع
- 📄 **رفع أي صيغة** من هاتفك: ملفات متعددة / 📂 مجلد كامل بكل المجلدات الفرعية / 🗜 zip مع فك الضغط أو رفعه كما هو
- 🎯 رفع لأي فرع (يُنشأ تلقائياً) وأي مجلد فرعي، شريط تقدم حي وتقرير كامل
- 🚀 التزامات مجمعة (Git Data API) — يعالج المجلدات الكبيرة بعدة التزامات تلقائياً

## ⬇ التنزيل والتثبيت
ملف APK الجاهز موجود في: [`apk/GitHubUploader-v1.0.apk`](../apk/GitHubUploader-v1.0.apk)

1. حمّل الملف على هاتفك
2. اسمح بتثبيت تطبيقات من "مصادر غير معروفة" لحظة التثبيت (لأنه خارج متجر Play)
3. ثبّته وافتحه

## 🔧 الإعداد لمرة واحدة (للدخول الرسمي عبر GitHub)
1. من [github.com/settings/developers](https://github.com/settings/developers) أنشئ **OAuth App** جديد
2. لا حاجة لـ Callback URL — اتركه فارغاً
3. **مهم**: فعّل خيار ✅ **Enable Device Flow** في صفحة التطبيق
4. انسخ **Client ID**، وفي التطبيق: زر الإعدادات ⚙ ثم الصقه واحفظ
5. اضغط 🔐 **تسجيل الدخول عبر GitHub** واتبع الرمز

> بديل بدون OAuth App: أنشئ Personal Access Token من [github.com/settings/tokens](https://github.com/settings/tokens) بصلاحية `repo` واستخدم خانة التوكن.

## 🛠 البناء من المصدر (اختياري)
```bash
cd android
./gradlew assembleRelease
```
- يتطلب JDK 17 وAndroid SDK (compileSdk 34)
- تم تضمين `uploader.keystore` لتوقيع ثابت للأحداث المقبلة (كلمة المرور داخل `app/build.gradle.kts`)
- يمكنك أيضاً فتح مجلد `android/` في Android Studio والبناء من الواجهة الرسومية
