package com.foodmap.review.entity;

import lombok.Data;

import java.time.Instant;

@Data
public class ReviewPhotoCandidate {
    private long reviewId;
    private int position;
    private long authorUserId;
    private String body;
    private Instant createdAt;
}
