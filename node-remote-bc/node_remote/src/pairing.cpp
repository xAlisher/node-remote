#include "pairing.h"

#include <QCryptographicHash>
#include <QMessageAuthenticationCode>
#include <QRandomGenerator>

#include <openssl/evp.h>
#include <openssl/rand.h>

namespace pairing {

namespace {
constexpr char kB32[] = "abcdefghijklmnopqrstuvwxyz234567";
}

QString base32Encode(const QByteArray& raw)
{
    QString out;
    int bits = 0;
    quint32 acc = 0;
    for (unsigned char c : raw) {
        acc = (acc << 8) | c;
        bits += 8;
        while (bits >= 5) {
            out.append(QLatin1Char(kB32[(acc >> (bits - 5)) & 0x1F]));
            bits -= 5;
        }
    }
    if (bits > 0) out.append(QLatin1Char(kB32[(acc << (5 - bits)) & 0x1F]));
    return out;
}

QByteArray base32Decode(const QString& s)
{
    QByteArray out;
    int bits = 0;
    quint32 acc = 0;
    for (QChar qc : s) {
        const char c = qc.toLower().toLatin1();
        const char* p = strchr(kB32, c);
        if (!p || c == '\0') continue;   // skip padding / stray chars
        acc = (acc << 5) | static_cast<quint32>(p - kB32);
        bits += 5;
        if (bits >= 8) {
            out.append(static_cast<char>((acc >> (bits - 8)) & 0xFF));
            bits -= 8;
        }
    }
    return out;
}

KeyPair generateClientAuthKey()
{
    KeyPair kp;
    EVP_PKEY* pkey = nullptr;
    EVP_PKEY_CTX* ctx = EVP_PKEY_CTX_new_id(EVP_PKEY_X25519, nullptr);
    if (!ctx) return kp;

    if (EVP_PKEY_keygen_init(ctx) <= 0 || EVP_PKEY_keygen(ctx, &pkey) <= 0) {
        EVP_PKEY_CTX_free(ctx);
        return kp;
    }
    EVP_PKEY_CTX_free(ctx);

    size_t len = 32;
    QByteArray priv(32, Qt::Uninitialized), pub(32, Qt::Uninitialized);
    bool ok = EVP_PKEY_get_raw_private_key(
                  pkey, reinterpret_cast<unsigned char*>(priv.data()), &len) > 0
              && len == 32;
    len = 32;
    ok = ok && EVP_PKEY_get_raw_public_key(
                   pkey, reinterpret_cast<unsigned char*>(pub.data()), &len) > 0
         && len == 32;
    EVP_PKEY_free(pkey);

    if (!ok) return kp;   // leave kp.ok == false; never a half-filled struct
    kp.privRaw = priv;
    kp.pubRaw = pub;
    kp.privBase32 = base32Encode(priv);
    kp.pubBase32 = base32Encode(pub);
    kp.ok = true;
    return kp;
}

QString randomToken(int bytes)
{
    QByteArray b(bytes, Qt::Uninitialized);
    // RAND_bytes, not QRandomGenerator: this is a bearer credential, and the Qt global
    // generator is not documented as cryptographically secure on every platform.
    if (RAND_bytes(reinterpret_cast<unsigned char*>(b.data()), bytes) != 1) return {};
    return QString::fromLatin1(b.toHex());
}

QString sas(const QString& enrollmentToken, const QString& onion)
{
    // HMAC over the onion keyed by the token: both ends know both values, an observer
    // who has only one of them cannot produce the digits, and swapping either the onion
    // (a MITM pointing at a different service) or the token changes the result.
    QMessageAuthenticationCode mac(QCryptographicHash::Sha256);
    mac.setKey(enrollmentToken.toUtf8());
    mac.addData(QByteArrayLiteral("lgnode/sas/v1|"));
    mac.addData(onion.toUtf8());
    const QByteArray d = mac.result();
    // Take 4 bytes -> 6 decimal digits, zero-padded.
    const quint32 v = (static_cast<quint8>(d[0]) << 24) | (static_cast<quint8>(d[1]) << 16)
                    | (static_cast<quint8>(d[2]) << 8) | static_cast<quint8>(d[3]);
    return QString("%1").arg(v % 1000000, 6, 10, QLatin1Char('0'));
}

bool secureEquals(const QByteArray& a, const QByteArray& b)
{
    if (a.size() != b.size()) return false;
    unsigned char diff = 0;
    for (int i = 0; i < a.size(); ++i)
        diff |= static_cast<unsigned char>(a[i] ^ b[i]);
    return diff == 0;
}

}  // namespace pairing
