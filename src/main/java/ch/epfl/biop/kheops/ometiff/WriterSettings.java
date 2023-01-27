package ch.epfl.biop.kheops.ometiff;

import ome.codecs.CompressionType;
import org.scijava.task.TaskService;

import java.io.File;
import java.io.IOException;

public class WriterSettings {

    final public int nThreads;
    final public String rangeC;
    final public String rangeZ;
    final public String rangeT;
    final public String path;
    final public int tileX; // = no tiling
    final public int tileY; // = no tiling
    final public String compression;
    final public boolean compressTempFiles;
    final public int maxTilesInQueue;
    final public TaskService taskService;
    final public int nResolutions;
    final public int downSample;

    private WriterSettings(int nThreads,
    String rangeC, String rangeZ, String rangeT,
    String path, int tileX, int tileY,
    String compression, boolean compressTempFiles,
    int maxTilesInQueue, TaskService taskService,
    int nResolutions, int downSample) {
        this.nThreads = nThreads;
        this.rangeC = rangeC;
        this.rangeZ = rangeZ;
        this.rangeT = rangeT;
        this.path = path;
        this.tileX = tileX;
        this.tileY = tileY;
        this.compression = compression;
        this.compressTempFiles = compressTempFiles;
        this.maxTilesInQueue = maxTilesInQueue;
        this.taskService = taskService;
        this.nResolutions = nResolutions;
        this.downSample = downSample;
    }
    public static WriterSettings.Builder builder() {
        return new WriterSettings.Builder();
    }

    public static class Builder {
        int nThreads = 0;
        String rangeC = "";
        String rangeZ = "";
        String rangeT = "";
        String path;
        int tileX = Integer.MAX_VALUE; // = no tiling
        int tileY = Integer.MAX_VALUE; // = no tiling
        String compression = "Uncompressed";
        boolean compressTempFiles = true;
        int maxTilesInQueue = 10;
        transient TaskService taskService = null;
        int nResolutions = 1;
        int downSample = 2;
        public WriterSettings.Builder nThreads(int nThreads) {
            this.nThreads = nThreads;
            return this;
        }

        public WriterSettings.Builder rangeC(String rangeC) {
            this.rangeC = rangeC;
            return this;
        }

        public WriterSettings.Builder rangeZ(String rangeZ) {
            this.rangeZ = rangeZ;
            return this;
        }

        public WriterSettings.Builder rangeT(String rangeT) {
            this.rangeT = rangeT;
            return this;
        }

        public WriterSettings.Builder tileSize(int tileX, int tileY) {
            this.tileX = tileX;
            this.tileY = tileY;
            return this;
        }

        public WriterSettings.Builder downsample(int downsample) {
            this.downSample = downsample;
            return this;
        }

        public WriterSettings.Builder nResolutionLevels(int nResolutions) {
            this.nResolutions = nResolutions;
            return this;
        }

        public WriterSettings.Builder lzw() {
            this.compression = CompressionType.LZW.getCompression();
            return this;
        }

        /**
         * see CompressionTypes
         *
         * @return the builder
         */
        public WriterSettings.Builder j2k() {
            this.compression = CompressionType.J2K.getCompression();
            return this;
        }

        /**
         * see CompressionTypes
         *
         * @return the builder
         */
        public WriterSettings.Builder j2kLossy() {
            this.compression = CompressionType.J2K_LOSSY.getCompression();
            return this;
        }

        /**
         * see CompressionTypes
         *
         * @return the builder
         */
        public WriterSettings.Builder jpg() {
            this.compression = CompressionType.JPEG.getCompression();
            return this;
        }

        public WriterSettings.Builder monitor(TaskService taskService) {
            this.taskService = taskService;
            return this;
        }

        public WriterSettings.Builder maxTilesInQueue(int max) {
            this.maxTilesInQueue = max;
            return this;
        }

        public WriterSettings.Builder compression(String compression) {
            this.compression = compression;
            return this;
        }

        public WriterSettings.Builder compression(int code) {
            this.compression = CompressionType.get(code).getCompression();
            return this;
        }

        public WriterSettings.Builder compressTemporaryFiles(boolean compressTempFile) {
            this.compressTempFiles = compressTempFile;
            return this;
        }

        public WriterSettings.Builder savePath(String path) {
            this.path = path;
            return this;
        }

        public WriterSettings build() throws Exception {
            if ((path == null)||(path.trim().equals(""))||(new File(path).exists())) {
                throw new IOException("Invalid path file");
            }
            return new WriterSettings(nThreads, rangeC, rangeZ, rangeT,
                path, tileX, tileY, compression, compressTempFiles,
                maxTilesInQueue, taskService, nResolutions, downSample);
        }
    }
}
