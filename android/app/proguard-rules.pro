# الحفاظ على كلاسات هذه المكدسة في حال واجهت استثناءات إعادة التسمية
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# OkHttp / Okio (تتوفر قواعد مستهلك داخلها، ومعدومات التوثيق)
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.*

# AndroidX Security Crypto
-keep class androidx.security.crypto.** { *; }

# JSON في أطراف التنفيذ عبر أسماء أعمدة نصية — org.json هو من أطر النظام ولا تُخلط
-dontwarn org.json.**
