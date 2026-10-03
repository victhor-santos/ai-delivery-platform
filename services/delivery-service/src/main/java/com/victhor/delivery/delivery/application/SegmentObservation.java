package com.victhor.delivery.delivery.application;

import com.victhor.delivery.delivery.domain.SegmentTraversal;

public record SegmentObservation(SegmentTraversal traversal, TraversalPrediction prediction) {
}
