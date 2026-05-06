#include <jni.h>

#include <algorithm>
#include <cmath>
#include <limits>
#include <unordered_set>
#include <vector>

namespace {
    constexpr double RELAX_EPSILON = 1e-12;

    std::vector<int> extractTriangularCycleEdgeIndexes(int startVertex,
                                                       int assetCount,
                                                       const std::vector<int>& predecessor,
                                                       const std::vector<int>& predecessorEdge) {
        int vertex = startVertex;
        for (int i = 0; i < assetCount; ++i) {
            vertex = predecessor[vertex];
            if (vertex < 0) {
                return {};
            }
        }

        std::vector<int> cycleVertices;
        int current = vertex;
        do {
            cycleVertices.push_back(current);
            current = predecessor[current];
            if (current < 0) {
                return {};
            }
        } while (current != vertex && cycleVertices.size() <= static_cast<size_t>(assetCount + 1));

        cycleVertices.push_back(vertex);
        std::reverse(cycleVertices.begin(), cycleVertices.end());

        if (cycleVertices.size() != 4) {
            return {};
        }

        std::unordered_set<int> distinctVertices;
        distinctVertices.insert(cycleVertices[0]);
        distinctVertices.insert(cycleVertices[1]);
        distinctVertices.insert(cycleVertices[2]);
        if (distinctVertices.size() != 3 || cycleVertices.front() != cycleVertices.back()) {
            return {};
        }

        std::vector<int> cycleEdgeIndexes;
        cycleEdgeIndexes.reserve(3);
        for (size_t i = 1; i < cycleVertices.size(); ++i) {
            int edgeIndex = predecessorEdge[cycleVertices[i]];
            if (edgeIndex < 0) {
                return {};
            }
            cycleEdgeIndexes.push_back(edgeIndex);
        }

        return cycleEdgeIndexes;
    }
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_xyz_cereshost_vesta_core_trading_abitrage_TriangularArbitrage_detectTriangularCyclesNative(
        JNIEnv* env,
        jclass,
        jint assetCount,
        jintArray edgeFromArray,
        jintArray edgeToArray,
        jdoubleArray edgeWeightsArray) {

    if (assetCount < 3 || edgeFromArray == nullptr || edgeToArray == nullptr || edgeWeightsArray == nullptr) {
        return env->NewIntArray(0);
    }

    const jsize edgeCount = env->GetArrayLength(edgeFromArray);
    if (edgeCount == 0
            || env->GetArrayLength(edgeToArray) != edgeCount
            || env->GetArrayLength(edgeWeightsArray) != edgeCount) {
        return env->NewIntArray(0);
    }

    std::vector<int> edgeFrom(static_cast<size_t>(edgeCount));
    std::vector<int> edgeTo(static_cast<size_t>(edgeCount));
    std::vector<double> edgeWeights(static_cast<size_t>(edgeCount));

    env->GetIntArrayRegion(edgeFromArray, 0, edgeCount, reinterpret_cast<jint*>(edgeFrom.data()));
    env->GetIntArrayRegion(edgeToArray, 0, edgeCount, reinterpret_cast<jint*>(edgeTo.data()));
    env->GetDoubleArrayRegion(edgeWeightsArray, 0, edgeCount, reinterpret_cast<jdouble*>(edgeWeights.data()));

    std::vector<int> packedCycles;
    packedCycles.reserve(static_cast<size_t>(assetCount) * 3);

    const int vertexCount = static_cast<int>(assetCount);
    for (int source = 0; source < vertexCount; ++source) {
        std::vector<double> distances(static_cast<size_t>(vertexCount), std::numeric_limits<double>::infinity());
        std::vector<int> predecessor(static_cast<size_t>(vertexCount), -1);
        std::vector<int> predecessorEdge(static_cast<size_t>(vertexCount), -1);
        distances[source] = 0.0;

        for (int i = 0; i < vertexCount - 1; ++i) {
            bool relaxed = false;
            for (int edgeIndex = 0; edgeIndex < edgeCount; ++edgeIndex) {
                const int from = edgeFrom[edgeIndex];
                const int to = edgeTo[edgeIndex];
                if (!std::isfinite(distances[from])) {
                    continue;
                }

                const double candidate = distances[from] + edgeWeights[edgeIndex];
                if (candidate + RELAX_EPSILON < distances[to]) {
                    distances[to] = candidate;
                    predecessor[to] = from;
                    predecessorEdge[to] = edgeIndex;
                    relaxed = true;
                }
            }
            if (!relaxed) {
                break;
            }
        }

        for (int edgeIndex = 0; edgeIndex < edgeCount; ++edgeIndex) {
            const int from = edgeFrom[edgeIndex];
            const int to = edgeTo[edgeIndex];
            if (!std::isfinite(distances[from])) {
                continue;
            }

            if (distances[from] + edgeWeights[edgeIndex] + RELAX_EPSILON < distances[to]) {
                predecessor[to] = from;
                predecessorEdge[to] = edgeIndex;
                std::vector<int> cycleEdges = extractTriangularCycleEdgeIndexes(to, vertexCount, predecessor, predecessorEdge);
                if (cycleEdges.size() == 3) {
                    packedCycles.insert(packedCycles.end(), cycleEdges.begin(), cycleEdges.end());
                }
            }
        }
    }

    jintArray result = env->NewIntArray(static_cast<jsize>(packedCycles.size()));
    if (result == nullptr || packedCycles.empty()) {
        return result;
    }

    env->SetIntArrayRegion(result, 0, static_cast<jsize>(packedCycles.size()), reinterpret_cast<const jint*>(packedCycles.data()));
    return result;
}
