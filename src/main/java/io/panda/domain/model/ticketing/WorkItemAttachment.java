package io.panda.domain.model.ticketing;

public record WorkItemAttachment(
    String id,
    String filename,
    String mimeType,
    String contentUrl,
    long sizeBytes
) {

    public boolean isImage() {
        return mimeType != null && mimeType.startsWith("image/");
    }
}
