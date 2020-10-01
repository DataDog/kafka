package org.apache.kafka.clients.consumer;

import java.nio.ByteBuffer;
import org.apache.kafka.common.utils.Utils;

public class ToArrayByteAllocator implements BytesAllocator {

    @Override
    public byte[] toArray(ByteBuffer bytes) {
        return Utils.toNullableArray(bytes);
    }
}
