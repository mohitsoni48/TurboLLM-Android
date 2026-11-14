#include "jni_text.h"

#include <optional>
#include <stdexcept>

namespace turbollm {

namespace {

constexpr char32_t replacement_character = 0xFFFD;
constexpr char32_t last_code_point       = 0x10FFFF;
constexpr char32_t first_supplementary   = 0x10000;

bool is_continuation(unsigned char byte) {
    return (byte & 0xC0) == 0x80;
}

bool is_surrogate(char32_t code_point) {
    return code_point >= 0xD800 && code_point <= 0xDFFF;
}

bool is_high_surrogate(char16_t unit) {
    return unit >= 0xD800 && unit <= 0xDBFF;
}

bool is_low_surrogate(char16_t unit) {
    return unit >= 0xDC00 && unit <= 0xDFFF;
}

// The sequence length a lead byte announces, or 0 for a byte that cannot start one.
size_t announced_length(unsigned char lead) {
    if (lead < 0x80) {
        return 1;
    }
    if (lead >= 0xC2 && lead <= 0xDF) {
        return 2;
    }
    if (lead >= 0xE0 && lead <= 0xEF) {
        return 3;
    }
    return lead >= 0xF0 && lead <= 0xF4 ? 4 : 0;
}

bool is_shortest_form(char32_t code_point, size_t length) {
    static constexpr char32_t smallest_for_length[] = { 0, 0, 0x80, 0x800, 0x10000 };
    return code_point >= smallest_for_length[length];
}

// The code point encoded at text[at], or nullopt for a malformed or truncated sequence.
std::optional<char32_t> code_point_at(const std::string & text, size_t at, size_t length) {
    if (length == 0 || at + length > text.size()) {
        return std::nullopt;
    }
    const auto lead       = static_cast<unsigned char>(text[at]);
    char32_t   code_point = length == 1 ? lead : lead & (0xFFu >> (length + 1));
    for (size_t offset = 1; offset < length; ++offset) {
        const auto next = static_cast<unsigned char>(text[at + offset]);
        if (!is_continuation(next)) {
            return std::nullopt;
        }
        code_point = (code_point << 6) | (next & 0x3Fu);
    }
    if (!is_shortest_form(code_point, length) || is_surrogate(code_point) || code_point > last_code_point) {
        return std::nullopt;
    }
    return code_point;
}

void append_utf16(std::u16string & out, char32_t code_point) {
    if (code_point < first_supplementary) {
        out.push_back(static_cast<char16_t>(code_point));
        return;
    }
    const char32_t offset = code_point - first_supplementary;
    out.push_back(static_cast<char16_t>(0xD800 + (offset >> 10)));
    out.push_back(static_cast<char16_t>(0xDC00 + (offset & 0x3FF)));
}

void append_continuation_bytes(std::string & out, char32_t code_point, int count) {
    for (int shift = 6 * (count - 1); shift >= 0; shift -= 6) {
        out.push_back(static_cast<char>(0x80 | ((code_point >> shift) & 0x3F)));
    }
}

void append_utf8(std::string & out, char32_t code_point) {
    if (code_point < 0x80) {
        out.push_back(static_cast<char>(code_point));
    } else if (code_point < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (code_point >> 6)));
        append_continuation_bytes(out, code_point, 1);
    } else if (code_point < first_supplementary) {
        out.push_back(static_cast<char>(0xE0 | (code_point >> 12)));
        append_continuation_bytes(out, code_point, 2);
    } else {
        out.push_back(static_cast<char>(0xF0 | (code_point >> 18)));
        append_continuation_bytes(out, code_point, 3);
    }
}

char32_t combine_surrogates(char16_t high, char16_t low) {
    const auto high_bits = static_cast<char32_t>(high - 0xD800) << 10;
    const auto low_bits  = static_cast<char32_t>(low - 0xDC00);
    return first_supplementary + (high_bits | low_bits);
}

std::u16string utf16_from_utf8(const std::string & text) {
    std::u16string out;
    out.reserve(text.size());
    size_t at = 0;
    while (at < text.size()) {
        const size_t length     = announced_length(static_cast<unsigned char>(text[at]));
        const auto   code_point = code_point_at(text, at, length);
        append_utf16(out, code_point.value_or(replacement_character));
        at += code_point ? length : 1;
    }
    return out;
}

std::string utf8_from_utf16(const std::u16string & text) {
    std::string out;
    out.reserve(text.size());
    for (size_t at = 0; at < text.size(); ++at) {
        const char16_t unit = text[at];
        if (is_high_surrogate(unit) && at + 1 < text.size() && is_low_surrogate(text[at + 1])) {
            append_utf8(out, combine_surrogates(unit, text[++at]));
        } else {
            append_utf8(out, is_surrogate(unit) ? replacement_character : unit);
        }
    }
    return out;
}

}  // namespace

std::string utf8_from_java(JNIEnv * env, jstring text) {
    if (text == nullptr) {
        throw std::invalid_argument("A null string reached the native session.");
    }
    std::u16string units(static_cast<size_t>(env->GetStringLength(text)), u'\0');
    env->GetStringRegion(text, 0, static_cast<jsize>(units.size()), reinterpret_cast<jchar *>(units.data()));
    return utf8_from_utf16(units);
}

jstring java_from_utf8(JNIEnv * env, const std::string & text) {
    const std::u16string units = utf16_from_utf8(text);
    return env->NewString(reinterpret_cast<const jchar *>(units.data()), static_cast<jsize>(units.size()));
}

}  // namespace turbollm
