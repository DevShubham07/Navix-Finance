package com.navix.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code navix.storage.*} configuration block.
 *
 * @param bucket            S3 bucket holding all DhanBoost documents/images
 * @param presignTtlSeconds validity of generated presigned URLs, in seconds
 * @param kmsKeyId          optional KMS key (ARN/alias) for server-side puts;
 *                          when blank, the bucket's default encryption applies
 */
@ConfigurationProperties(prefix = "navix.storage")
public record StorageProperties(
        String bucket,
        long presignTtlSeconds,
        String kmsKeyId
) {
}
