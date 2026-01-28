package com.kooo.evcam.camera;

import java.util.List;

/**
 * 录制回调接口
 */
public interface RecordCallback {
    /**
     * 录制开始
     */
    void onRecordStart(String cameraId);

    /**
     * 录制停止
     */
    void onRecordStop(String cameraId);

    /**
     * 录制错误
     */
    void onRecordError(String cameraId, String error);

    /**
     * 分段切换（需要重新配置相机会话）
     * @param cameraId 相机ID
     * @param newSegmentIndex 新的分段索引
     * @param completedFilePath 已完成的文件路径（可用于传输到最终目录）
     */
    void onSegmentSwitch(String cameraId, int newSegmentIndex, String completedFilePath);

    /**
     * 损坏文件被删除
     * @param cameraId 相机ID
     * @param deletedFiles 被删除的文件名列表
     */
    void onCorruptedFilesDeleted(String cameraId, List<String> deletedFiles);
}
