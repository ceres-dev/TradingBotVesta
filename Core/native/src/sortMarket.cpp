#include <array>
#include <cstddef>
#include <cstdint>
#include <numeric>
#include <stdexcept>
#include <vector>

namespace vesta {
namespace native_sort {

static inline std::uint64_t toSortableKey(std::int64_t value) {
    // Convierte signed -> unsigned preservando orden total.
    return static_cast<std::uint64_t>(value) ^ 0x8000000000000000ULL;
}

std::vector<std::size_t> radixSortIndicesByTimestamp(const std::vector<std::int64_t>& timestamps) {
    const std::size_t n = timestamps.size();
    std::vector<std::size_t> indices(n);
    std::vector<std::size_t> tmp(n);
    std::iota(indices.begin(), indices.end(), 0);

    if (n <= 1) {
        return indices;
    }

    constexpr std::size_t RADIX = 256;
    std::array<std::size_t, RADIX> count{};
    std::array<std::size_t, RADIX> start{};

    // Radix LSD simple: 8 pasadas de 8 bits para int64.
    for (int pass = 0; pass < 8; ++pass) {
        const int shift = pass * 8;
        count.fill(0);

        for (std::size_t i = 0; i < n; ++i) {
            const auto key = toSortableKey(timestamps[indices[i]]);
            const std::size_t bucket = static_cast<std::size_t>((key >> shift) & 0xFFULL);
            ++count[bucket];
        }

        std::size_t running = 0;
        for (std::size_t b = 0; b < RADIX; ++b) {
            start[b] = running;
            running += count[b];
        }

        for (std::size_t i = 0; i < n; ++i) {
            const auto key = toSortableKey(timestamps[indices[i]]);
            const std::size_t bucket = static_cast<std::size_t>((key >> shift) & 0xFFULL);
            tmp[start[bucket]++] = indices[i];
        }

        indices.swap(tmp);
    }

    return indices;
}

template <typename T>
void reorderByIndices(std::vector<T>& values, const std::vector<std::size_t>& sortedIndices) {
    if (values.size() != sortedIndices.size()) {
        throw std::invalid_argument("values and sortedIndices must have same size");
    }

    std::vector<T> ordered(values.size());
    for (std::size_t i = 0; i < sortedIndices.size(); ++i) {
        ordered[i] = values[sortedIndices[i]];
    }
    values.swap(ordered);
}

void sortTimestampsInPlace(std::vector<std::int64_t>& timestamps) {
    const auto order = radixSortIndicesByTimestamp(timestamps);
    reorderByIndices(timestamps, order);
}

}  // namespace native_sort
}  // namespace vesta

extern "C" {

// Ordena timestamps in-place (ascendente) con radix simple.
void sortMarketRadixInt64InPlace(std::int64_t* timestamps, std::size_t size) {
    if (timestamps == nullptr || size <= 1) {
        return;
    }

    std::vector<std::int64_t> buffer(timestamps, timestamps + size);
    vesta::native_sort::sortTimestampsInPlace(buffer);

    for (std::size_t i = 0; i < size; ++i) {
        timestamps[i] = buffer[i];
    }
}

// Returns sorted permutation by timestamps (original indices).
void sortMarketRadixInt64Indices(
        const std::int64_t* timestamps,
        std::size_t size,
        std::size_t* outSortedIndices
) {
    if (timestamps == nullptr || outSortedIndices == nullptr || size == 0) {
        return;
    }

    std::vector<std::int64_t> buffer(timestamps, timestamps + size);
    const auto order = vesta::native_sort::radixSortIndicesByTimestamp(buffer);
    for (std::size_t i = 0; i < size; ++i) {
        outSortedIndices[i] = order[i];
    }
}

}  // extern "C"
