package pqc.gateway;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import pqc.crypto.CryptoException;
import pqc.crypto.CryptoMode;
import pqc.crypto.SignatureValue;
import pqc.crypto.Signer;
import pqc.gateway.GatewayProperties.Corporation;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이체 요청 검증. 순서는 전문 규격 5장 1~5단계를 따른다.
 * <ol start="0">
 * <li>구조: 본문을 읽고 security 객체와 기업 ID의 형식을 본다 → E1000</li>
 * <li>암호 모드: 허용 값이고 기업의 최저 모드 이상인가 → E1003 (T2)</li>
 * <li>시간: timestamp가 앞뒤 5분 안인가 → E1002 (T3)</li>
 * <li>서명: kid가 그 기업의 것이고, 모드가 요구하는 서명이 모두 유효한가 → E1001 (T5, T2)</li>
 * <li>nonce: 처음 보는가. 서명 확인 뒤에 등록한다 → E1002 (T3)</li>
 * <li>본문: transfer 항목이 제약을 지키는가 → E1000</li>
 * </ol>
 * 등록되지 않은 기업은 서명을 확인할 수 없으므로 E1001로 거부한다.
 */
public final class RequestVerifier {

    /** JCS는 숫자를 IEEE 754 배정밀도로 다루므로, 그 범위를 넘는 정수는 서명 양쪽에서 값이 달라질 수 있다. */
    static final long MAX_SAFE_INTEGER = (1L << 53) - 1;

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION) // 같은 키가 두 번이면 어느 값을 서명했는지 모호하다
            .build();

    private static final Set<String> ENVELOPE_FIELDS = Set.of("security", "transfer");
    private static final Set<String> SECURITY_FIELDS = Set.of("crypto_mode", "session_id", "timestamp", "nonce", "signatures");
    private static final Set<String> SIGNATURE_FIELDS = Set.of("alg", "kid", "value");
    private static final Set<String> TRANSFER_FIELDS = Set.of("idempotency_key", "corporation_id", "from_account_no",
            "to_bank_code", "to_account_no", "amount", "currency", "memo", "requested_at");

    private static final Pattern ACCOUNT_NO = Pattern.compile("[0-9]{10,16}");
    private static final Pattern BANK_CODE = Pattern.compile("[0-9]{3}");
    private static final Pattern AMOUNT = Pattern.compile("[1-9][0-9]{0,14}");

    private final CorporationDirectory corporations;
    private final Signer signer;
    private final NonceStore nonces;
    private final Clock clock;
    private final Duration clockSkew;
    private final Duration nonceTtl;

    public RequestVerifier(CorporationDirectory corporations, Signer signer, NonceStore nonces, Clock clock,
            Duration clockSkew, Duration nonceTtl) {
        this.corporations = corporations;
        this.signer = signer;
        this.nonces = nonces;
        this.clock = clock;
        this.clockSkew = clockSkew;
        this.nonceTtl = nonceTtl;
    }

    public VerifiedTransfer verify(String body) {
        // 0. 구조
        JsonNode envelope = parse(body);
        requireObject(envelope, "본문", ENVELOPE_FIELDS, ENVELOPE_FIELDS);
        JsonNode security = envelope.get("security");
        JsonNode transfer = envelope.get("transfer");
        requireObject(security, "security", SECURITY_FIELDS, SECURITY_FIELDS);
        requireObject(transfer, "transfer", TRANSFER_FIELDS, Set.of());
        String modeText = text(security, "crypto_mode", 1, 16);
        text(security, "session_id", 1, 64);
        String nonce = text(security, "nonce", 16, 64);
        Instant timestamp = instant(security, "timestamp");
        List<SignatureValue> signatures = signatures(security.get("signatures"));
        long corporationId = corporationId(transfer);

        // 1. 암호 모드
        CryptoMode mode;
        try {
            mode = CryptoMode.valueOf(modeText);
        } catch (IllegalArgumentException e) {
            throw new GatewayException(ResultCode.E1003, "알 수 없는 암호 모드: " + modeText);
        }
        Corporation corporation = corporations.find(corporationId)
                .orElseThrow(() -> new GatewayException(ResultCode.E1001, "등록되지 않은 기업: " + corporationId));
        if (!mode.isAtLeast(corporation.floor())) {
            throw new GatewayException(ResultCode.E1003,
                    "최저 모드 미만: 기업 " + corporationId + ", 요청 " + mode + ", 최저 " + corporation.floor());
        }

        // 2. 시간
        Duration drift = Duration.between(clock.instant(), timestamp).abs();
        if (drift.compareTo(clockSkew) > 0) {
            throw new GatewayException(ResultCode.E1002, "timestamp 범위 밖: 차이 " + drift.toSeconds() + "초");
        }

        // 3. 서명
        for (SignatureValue sig : signatures) {
            if (!corporation.kids().contains(sig.kid())) {
                throw new GatewayException(ResultCode.E1001, "기업 " + corporationId + "의 kid가 아님: " + sig.kid());
            }
        }
        byte[] signed = SigningInput.of(envelope);
        try {
            signer.verify(mode, signed, signatures);
        } catch (CryptoException e) {
            throw new GatewayException(ResultCode.E1001, "서명 검증 실패: " + e.getMessage(), e);
        }

        // 4. nonce (서명 확인 뒤에 등록해야 가짜 요청이 nonce를 선점하지 못한다)
        boolean first;
        try {
            first = nonces.register(corporationId + ":" + nonce, nonceTtl);
        } catch (RuntimeException e) {
            throw new GatewayException(ResultCode.E9000, "nonce 저장소 오류", e);
        }
        if (!first) {
            throw new GatewayException(ResultCode.E1002, "이미 쓴 nonce: 기업 " + corporationId);
        }

        // 5. 본문
        validateTransfer(transfer);

        return new VerifiedTransfer(corporationId, mode, JSON.writeValueAsString(transfer), sha256Hex(signed));
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw format("본문 없음");
        }
        try {
            return JSON.readTree(body);
        } catch (JacksonException e) {
            throw new GatewayException(ResultCode.E1000, "JSON 형식 오류: " + e.getOriginalMessage(), e);
        }
    }

    /** 객체이고, allowed 밖의 키가 없고, required 키가 모두 있어야 한다. */
    private static void requireObject(JsonNode node, String name, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) {
            throw format(name + "는 객체여야 함");
        }
        for (String key : node.propertyNames()) {
            if (!allowed.contains(key)) {
                throw format(name + "에 정의되지 않은 항목: " + key);
            }
        }
        for (String key : required) {
            if (!node.has(key)) {
                throw format(name + "." + key + " 없음");
            }
        }
    }

    private static String text(JsonNode parent, String field, int min, int max) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isString()) {
            throw format(field + "는 문자열이어야 함");
        }
        String value = node.asString();
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max) {
            throw format(field + " 길이는 " + min + "~" + max + "자");
        }
        if (value.codePoints().anyMatch(RequestVerifier::forbidden)) {
            throw format(field + "에 쓸 수 없는 문자(제어 문자 또는 짝 없는 서로게이트)");
        }
        return value;
    }

    /**
     * 제어 문자(U+0000~U+001F, U+007F~U+009F)와 짝 없는 서로게이트는 모든 문자열에서 거부한다.
     * NUL은 PostgreSQL이 저장하지 못해 이체 API가 따로 처리하지 않으면 500이 되고, 줄바꿈은 로그·화면을 어지럽힌다.
     * 짝 없는 서로게이트는 UTF-8로 바꿀 수 없어 이체 API로 넘길 때 '?'로 바뀐다(서명한 값과 넘긴 값이 달라짐).
     * JCS(RFC 8785)도 이런 문자열을 허용하지 않는 I-JSON을 전제로 한다.
     */
    private static boolean forbidden(int codePoint) {
        return Character.isISOControl(codePoint)
                || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE);
    }

    private static Instant instant(JsonNode parent, String field) {
        String value = text(parent, field, 1, 40);
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw format(field + "는 ISO 8601 시각이어야 함");
        }
    }

    private static List<SignatureValue> signatures(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > 2) {
            throw format("signatures는 1~2개의 배열이어야 함");
        }
        List<SignatureValue> result = new ArrayList<>();
        for (JsonNode item : node) {
            requireObject(item, "signatures[]", SIGNATURE_FIELDS, SIGNATURE_FIELDS);
            String alg = text(item, "alg", 1, 16);
            String kid = text(item, "kid", 1, 64);
            byte[] value;
            try {
                value = Base64.getDecoder().decode(text(item, "value", 1, 16_384));
            } catch (IllegalArgumentException e) {
                throw format("signatures[].value는 base64여야 함");
            }
            result.add(new SignatureValue(alg, kid, value));
        }
        return result;
    }

    private static long corporationId(JsonNode transfer) {
        JsonNode node = transfer.get("corporation_id");
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw format("transfer.corporation_id는 정수여야 함");
        }
        long id = node.asLong();
        if (id < 1 || id > MAX_SAFE_INTEGER) {
            throw format("transfer.corporation_id 범위 밖");
        }
        return id;
    }

    /** openapi.yaml TransferRequest 스키마와 같은 제약. */
    private static void validateTransfer(JsonNode t) {
        text(t, "idempotency_key", 8, 64);
        match(t, "from_account_no", ACCOUNT_NO);
        match(t, "to_bank_code", BANK_CODE);
        match(t, "to_account_no", ACCOUNT_NO);
        match(t, "amount", AMOUNT);
        if (!"KRW".equals(text(t, "currency", 3, 3))) {
            throw format("currency는 KRW만 허용");
        }
        if (t.has("memo")) {
            text(t, "memo", 0, 20);
        }
        instant(t, "requested_at");
    }

    private static void match(JsonNode parent, String field, Pattern pattern) {
        String value = text(parent, field, 1, 64);
        if (!pattern.matcher(value).matches()) {
            throw format(field + " 형식 오류");
        }
    }

    private static GatewayException format(String reason) {
        return new GatewayException(ResultCode.E1000, reason);
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
