package com.ginkgo.foodmap.user.policy;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.user.exception.UserErrorCode;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** 简介基础规则与脱敏；识别副本用于常见联系方式检查，不改写用户的正常Unicode文本。 */
public final class UserProfilePolicy {
    private UserProfilePolicy() { }
    private static final Pattern EDGES = Pattern.compile("(?U)^[\\s\\uFEFF]+|[\\s\\uFEFF]+$");
    private static final Pattern SPACES_AND_FORMATS = Pattern.compile("(?U)[\\s\\p{Cf}]+");
    private static final Pattern LINK = Pattern.compile(
            "[a-z][a-z0-9+.-]*://|www\\.|(?:mailto|tel|weixin|mqq|qq):|" +
            "(?:[a-z0-9\\p{IsHan}](?:[a-z0-9\\p{IsHan}-]*[a-z0-9\\p{IsHan}])?\\.)+(?:[a-z]{2,24}|中国|公司|网络)(?![a-z0-9-])|" +
            "(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])");
    private static final Pattern MOBILE = Pattern.compile("(?<![0-9])(?:\\+?86)?1[3-9][0-9]{9}(?![0-9])");
    private static final Pattern CONTACT = Pattern.compile(
            "(?:微信(?:号|号码)?|wechat|wx|vx|v信|加v|加微)[:=]?([a-z][a-z0-9_-]{5,19}|[0-9]{6,20})|" +
            "(?<![a-z0-9])wxid_[a-z0-9_-]{6,}|" +
            "(?:qq(?:号码|群号|号|群)?|企鹅号?)[:=]?[1-9][0-9]{4,11}(?![0-9])|" +
            "(?:手机(?:号)?|电话|tel|联系方式)[:=]?[+0-9][0-9()-]{5,}");

    /** 首尾空白移除、CRLF统一为LF；长度按码点计算，空串表示清除简介。 */
    public static String bio(String value) {
        if (value == null) throw new BusinessException(UserErrorCode.BIO_INVALID);
        String text = EDGES.matcher(value.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("");
        if (text.codePointCount(0, text.length()) > 100) throw new BusinessException(UserErrorCode.BIO_TOO_LONG);
        for (int offset = 0; offset < text.length();) {
            int point = text.codePointAt(offset);
            if ((Character.isISOControl(point) && point != '\n' && point != '\t')
                    || (point >= 0xD800 && point <= 0xDFFF)) throw new BusinessException(UserErrorCode.BIO_INVALID);
            offset += Character.charCount(point);
        }
        // NFKC识别全角数字/标点；去掉间隔空白及格式字符检查，但保留存储中的emoji连接符等文本。
        String probe = SPACES_AND_FORMATS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll("").toLowerCase(Locale.ROOT);
        String phone = probe.replaceAll("(?<=[0-9])[._()-](?=[0-9])", "");
        if (LINK.matcher(probe).find() || MOBILE.matcher(phone).find() || CONTACT.matcher(probe).find())
            throw new BusinessException(UserErrorCode.BIO_CONTACT_NOT_ALLOWED);
        return text;
    }

    /** 只保留长本地部分的首个字符和邮箱域名；短/损坏邮箱不返回完整本地部分。 */
    public static String maskedEmail(String value) {
        if (value == null) return "***";
        int at = value.lastIndexOf('@');
        if (at <= 0 || at == value.length()-1) return "***";
        String local = value.substring(0, at);
        String first = local.codePointCount(0, local.length()) > 2 ? new String(Character.toChars(local.codePointAt(0))) : "";
        return first + "***" + value.substring(at);
    }
}
