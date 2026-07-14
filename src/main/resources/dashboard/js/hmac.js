

(function (global) {
    'use strict';

    function rotr(n, x) { return (x >>> n) | (x << (32 - n)); }

    var K = new Uint32Array([
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    ]);

    function sha256(bytes) {

        var len = bytes.length;
        var bitLen = len * 8;

        var padLen = (len % 64 < 56) ? (64 - len % 64) : (128 - len % 64);
        var padded = new Uint8Array(len + padLen);
        padded.set(bytes);
        padded[len] = 0x80;

        var lo = bitLen >>> 0;
        padded[padded.length - 4] = (lo >>> 24) & 0xff;
        padded[padded.length - 3] = (lo >>> 16) & 0xff;
        padded[padded.length - 2] = (lo >>> 8) & 0xff;
        padded[padded.length - 1] = lo & 0xff;

        var H = new Uint32Array([
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
            0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
        ]);

        var W = new Uint32Array(64);
        var dv = new DataView(padded.buffer);

        for (var i = 0; i < padded.length; i += 64) {
            for (var t = 0; t < 16; t++) {
                W[t] = dv.getUint32(i + t * 4, false);
            }
            for (var t = 16; t < 64; t++) {
                var s0 = rotr(7, W[t - 15]) ^ rotr(18, W[t - 15]) ^ (W[t - 15] >>> 3);
                var s1 = rotr(17, W[t - 2]) ^ rotr(19, W[t - 2]) ^ (W[t - 2] >>> 10);
                W[t] = (W[t - 16] + s0 + W[t - 7] + s1) >>> 0;
            }

            var a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
            for (var t = 0; t < 64; t++) {
                var S1 = rotr(6, e) ^ rotr(11, e) ^ rotr(25, e);
                var ch = (e & f) ^ ((~e) & g);
                var temp1 = (h + S1 + ch + K[t] + W[t]) >>> 0;
                var S0 = rotr(2, a) ^ rotr(13, a) ^ rotr(22, a);
                var maj = (a & b) ^ (a & c) ^ (b & c);
                var temp2 = (S0 + maj) >>> 0;
                h = g; g = f; f = e; e = (d + temp1) >>> 0;
                d = c; c = b; b = a; a = (temp1 + temp2) >>> 0;
            }
            H[0] = (H[0] + a) >>> 0;
            H[1] = (H[1] + b) >>> 0;
            H[2] = (H[2] + c) >>> 0;
            H[3] = (H[3] + d) >>> 0;
            H[4] = (H[4] + e) >>> 0;
            H[5] = (H[5] + f) >>> 0;
            H[6] = (H[6] + g) >>> 0;
            H[7] = (H[7] + h) >>> 0;
        }

        var out = new Uint8Array(32);
        var odv = new DataView(out.buffer);
        for (var i = 0; i < 8; i++) {
            odv.setUint32(i * 4, H[i], false);
        }
        return out;
    }

    function hmacSha256(keyStr, dataStr) {

        var enc = new TextEncoder();
        var key = enc.encode(keyStr);
        var data = enc.encode(dataStr);

        if (key.length > 64) {
            key = sha256(key);
        }

        var paddedKey = new Uint8Array(64);
        paddedKey.set(key);

        var ipad = new Uint8Array(64);
        var opad = new Uint8Array(64);
        for (var i = 0; i < 64; i++) {
            ipad[i] = paddedKey[i] ^ 0x36;
            opad[i] = paddedKey[i] ^ 0x5c;
        }

        var inner = new Uint8Array(64 + data.length);
        inner.set(ipad);
        inner.set(data, 64);
        var innerHash = sha256(inner);

        var outer = new Uint8Array(64 + 32);
        outer.set(opad);
        outer.set(innerHash, 64);
        var outerHash = sha256(outer);

        var hex = '';
        for (var i = 0; i < outerHash.length; i++) {
            hex += outerHash[i].toString(16).padStart(2, '0');
        }
        return hex;
    }

    global.HmacSha256 = hmacSha256;

})(typeof window !== 'undefined' ? window : this);
