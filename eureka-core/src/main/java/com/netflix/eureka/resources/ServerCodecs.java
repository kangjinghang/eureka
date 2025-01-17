package com.netflix.eureka.resources;

import com.netflix.appinfo.EurekaAccept;
import com.netflix.discovery.converters.wrappers.CodecWrapper;
import com.netflix.discovery.converters.wrappers.EncoderWrapper;
import com.netflix.eureka.registry.Key;

/**
 * @author David Liu
 */
public interface ServerCodecs { // Eureka-Server 请求和响应编解码器

    CodecWrapper getFullJsonCodec();

    CodecWrapper getCompactJsonCodec();

    CodecWrapper getFullXmlCodec();

    CodecWrapper getCompactXmlCodecr();

    EncoderWrapper getEncoder(Key.KeyType keyType, boolean compact);

    EncoderWrapper getEncoder(Key.KeyType keyType, EurekaAccept eurekaAccept);
}
