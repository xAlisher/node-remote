#pragma once

// Pairing: turn a QR scan into an authorized device.
//
// The chicken-and-egg that shapes this: once client authorization is on, a phone whose
// key is not yet in authorized_clients/ cannot reach the onion AT ALL — so it cannot
// negotiate anything. The QR therefore has to carry a client-auth PRIVATE key that the
// desktop generated and pre-authorized. The QR is the bearer credential; that is
// inherent to the design, not an oversight.
//
// Which is exactly why the 6-digit SAS + desktop approval exists: a photographed QR
// still cannot pair silently, because an approval prompt the user did not expect
// appears on the desktop and they deny it.
//
// Key type is X25519 via OpenSSL — libcrypto is already linked and bundled in the .lgx,
// so this adds no new runtime dependency.

#include <QByteArray>
#include <QDateTime>
#include <QString>

namespace pairing {

struct KeyPair {
    QByteArray privRaw;   // 32 bytes
    QByteArray pubRaw;    // 32 bytes
    QString    privBase32;  // what the phone feeds to kmp-tor's ONION_CLIENT_AUTH_ADD
    QString    pubBase32;   // what goes in <hsDir>/authorized_clients/<name>.auth
    bool       ok = false;
};

/// Generate an X25519 client-auth keypair. Returns ok=false on any OpenSSL failure —
/// never a half-filled struct, so a caller cannot accidentally authorize garbage.
KeyPair generateClientAuthKey();

/// RFC 4648 base32, lowercase, unpadded — the form tor's client-auth files use.
QString base32Encode(const QByteArray& raw);
QByteArray base32Decode(const QString& s);

/// Cryptographically random token, hex. Used for the one-time enrollment token and for
/// long-lived device tokens.
QString randomToken(int bytes = 24);

/// 6-digit short authentication string shown on BOTH desktop and phone. Derived from the
/// enrollment token and the onion so it cannot be precomputed, and so a MITM that swapped
/// either value produces different digits.
QString sas(const QString& enrollmentToken, const QString& onion);

/// Constant-time compare. Token checks must not leak length or prefix by timing.
bool secureEquals(const QByteArray& a, const QByteArray& b);

}  // namespace pairing
