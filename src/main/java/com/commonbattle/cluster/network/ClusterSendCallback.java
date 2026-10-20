package com.commonbattle.cluster.network;

/**
 * 跨服信封写入结果回调。
 * 传输实现必须且只能完成一次；调用方用失败回调及时释放 RPC 等待状态。
 */
public interface ClusterSendCallback {
    ClusterSendCallback NOOP = new ClusterSendCallback() {
        @Override
        public void success() {
        }

        @Override
        public void failure(Throwable error) {
        }
    };

    void success();

    void failure(Throwable error);
}
