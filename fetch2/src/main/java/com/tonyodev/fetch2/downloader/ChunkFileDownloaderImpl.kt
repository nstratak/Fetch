package com.tonyodev.fetch2.downloader

import android.util.Log
import com.tonyodev.fetch2.Download
import com.tonyodev.fetch2.Downloader
import com.tonyodev.fetch2.Error
import com.tonyodev.fetch2.Logger
import com.tonyodev.fetch2.exception.FetchException
import com.tonyodev.fetch2.getErrorFromThrowable
import com.tonyodev.fetch2.provider.NetworkInfoProvider
import com.tonyodev.fetch2.util.*
import java.io.*
import java.net.HttpURLConnection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.roundToLong

class ChunkFileDownloaderImpl(private val initialDownload: Download,
                              private val downloader: Downloader,
                              private val progressReportingIntervalMillis: Long,
                              private val downloadBufferSizeBytes: Int,
                              private val logger: Logger,
                              private val networkInfoProvider: NetworkInfoProvider,
                              private val retryOnNetworkGain: Boolean,
                              private val fileChunkTempDir: String) : FileDownloader {

    @Volatile
    override var interrupted = false

    @Volatile
    override var terminated = false

    @Volatile
    override var completedDownload = false

    override var delegate: FileDownloader.Delegate? = null

    private var downloadInfo = initialDownload.toDownloadInfo()

    override val download: Download
        get () {
            downloadInfo.downloaded = getProgressDownloaded()
            downloadInfo.total = total
            return downloadInfo
        }

    @Volatile
    private var downloaded = 0L

    private val downloadedLock = Object()

    private var total = 0L

    private var averageDownloadedBytesPerSecond = 0.0

    private val movingAverageCalculator = AverageCalculator(5)

    private var estimatedTimeRemainingInMilliseconds: Long = -1

    private var chunkExecutorService: ExecutorService? = null

    private var phase = Phase.DOWNLOADING

    private var fileChunks = listOf<FileChuck>()

    override fun run() {
        var openingResponse: Downloader.Response? = null
        var output: OutputStream? = null
        var randomAccessFile: RandomAccessFile? = null
        try {
            val openingRequest = getOpeningRequest()
            openingResponse = downloader.execute(openingRequest)
            if (!interrupted && !terminated && openingResponse?.isSuccessful == true) {
                total = openingResponse.contentLength
                if (total > 0) {
                    downloadInfo.total = total
                    fileChunks = getFileChunkList(openingResponse.code, openingRequest)
                    downloadInfo.downloaded = getProgressDownloaded()
                    val chunkDownloadsList = fileChunks.filter { !it.completed }
                    if (!interrupted && !terminated) {
                        delegate?.onStarted(
                                download = downloadInfo,
                                etaInMilliseconds = estimatedTimeRemainingInMilliseconds,
                                downloadedBytesPerSecond = getAverageDownloadedBytesPerSecond())
                        chunkExecutorService = Executors.newFixedThreadPool(chunkDownloadsList.size + 1)
                        downloadChunks(chunkDownloadsList)
                        waitAndPerformProgressReporting(fileChunks)
                        if (!interrupted && !terminated) {
                            val downloadedBytesSum = fileChunks.asSequence().map { it.downloaded }.sum()
                            if (downloadedBytesSum == total) {
                                downloaded = downloadedBytesSum
                                downloadInfo.downloaded = getProgressDownloaded()
                                output = downloader.getRequestOutputStream(openingRequest, 0)
                                if (output == null) {
                                    randomAccessFile = RandomAccessFile(getFile(downloadInfo.file), "rw")
                                    randomAccessFile.seek(0)
                                }
                                phase = Phase.MERGING
                                fileChunks.forEach { it.status = Status.MERGING }
                                chunkExecutorService?.execute({
                                    for (fileChunk in fileChunks) {
                                        if (!interrupted && !terminated) {
                                            mergeChunk(fileChunk, output, randomAccessFile)
                                            fileChunk.status = Status.MERGED
                                        } else {
                                            fileChunks.forEach { it.status = Status.QUEUED }
                                            break
                                        }
                                    }
                                })
                                waitAndPerformProgressReporting(fileChunks)
                                if (!interrupted && !terminated) {
                                    val allMerged = fileChunks.filter { it.status == Status.MERGED }.count() == fileChunks.size
                                    if (allMerged) {
                                        completedDownload = true
                                        phase = Phase.COMPLETED
                                        if (!terminated) {
                                            delegate?.onProgress(
                                                    download = downloadInfo,
                                                    etaInMilliSeconds = estimatedTimeRemainingInMilliseconds,
                                                    downloadedBytesPerSecond = getAverageDownloadedBytesPerSecond())
                                            delegate?.onComplete(
                                                    download = downloadInfo)
                                        }
                                        for (fileChunk in fileChunks) {
                                            val file = File(fileChunk.file)
                                            if (file.exists()) {
                                                file.delete()
                                            }
                                            val text = getFile("${getFileForChunk(fileChunk.id, fileChunk.position)}.txt")
                                            if (text.exists()) {
                                                text.delete()
                                            }
                                        }
                                        deleteTempDirForId(initialDownload.id)
                                    }
                                }
                            }
                        }
                        downloadInfo.downloaded = getProgressDownloaded()
                        delegate?.saveDownloadProgress(downloadInfo)
                        if (!completedDownload && !terminated) {
                            delegate?.onProgress(
                                    download = downloadInfo,
                                    etaInMilliSeconds = estimatedTimeRemainingInMilliseconds,
                                    downloadedBytesPerSecond = getAverageDownloadedBytesPerSecond())
                        }
                        val failedChunk = fileChunks.firstOrNull { it.status == Status.ERROR }
                        if (failedChunk != null) {
                            throw failedChunk.errorException!!
                        }
                    }
                } else {
                    throw FetchException(EMPTY_RESPONSE_BODY,
                            FetchException.Code.EMPTY_RESPONSE_BODY)
                }
            } else if (openingResponse == null && !interrupted && !terminated) {
                throw FetchException(EMPTY_RESPONSE_BODY,
                        FetchException.Code.EMPTY_RESPONSE_BODY)
            } else if (openingResponse?.isSuccessful == false && !interrupted && !terminated) {
                throw FetchException(RESPONSE_NOT_SUCCESSFUL,
                        FetchException.Code.REQUEST_NOT_SUCCESSFUL)
            } else if (!interrupted && !terminated) {
                throw FetchException(UNKNOWN_ERROR,
                        FetchException.Code.UNKNOWN)
            }
        } catch (e: Exception) {
            if (!interrupted && !terminated) {
                logger.e("FileDownloader", e)
                var error = getErrorFromThrowable(e)
                error.throwable = e
                if (retryOnNetworkGain) {
                    var disconnectDetected = !networkInfoProvider.isNetworkAvailable
                    for (i in 1..10) {
                        try {
                            Thread.sleep(500)
                        } catch (e: InterruptedException) {
                            logger.e("FileDownloader", e)
                            break
                        }
                        if (!networkInfoProvider.isNetworkAvailable) {
                            disconnectDetected = true
                            break
                        }
                    }
                    if (disconnectDetected) {
                        error = Error.NO_NETWORK_CONNECTION
                    }
                }
                downloadInfo.downloaded = getProgressDownloaded()
                downloadInfo.error = error
                if (!terminated) {
                    delegate?.onError(download = downloadInfo)
                }
            }
        } finally {
            chunkExecutorService?.shutdown()
            if (openingResponse != null) {
                try {
                    downloader.disconnect(openingResponse)
                } catch (e: Exception) {
                    logger.e("FileDownloader", e)
                }
            }
            try {
                output?.close()
            } catch (e: Exception) {
                logger.e("FileDownloader", e)
            }
            try {
                randomAccessFile?.close()
            } catch (e: Exception) {
                logger.e("FileDownloader", e)
            }
            terminated = true
        }
    }

    private fun getProgressDownloaded(): Long {
        return when (phase) {
            Phase.DOWNLOADING -> {
                val actualProgress = calculateProgress(downloaded, total)
                val downloadedPercentageTotal = (0.9F * total.toFloat())
                val downloaded = (actualProgress.toFloat() / 100.toFloat()) * downloadedPercentageTotal
                downloaded.roundToLong()
            }
            Phase.MERGING -> {
                val downloadedTotal = (0.9F * total.toFloat())
                val onePercentOfTotal = (0.01F * total.toFloat())
                val mergeAllocTotal = 10F / fileChunks.size.toFloat()
                val completedMerge = fileChunks.filter { it.status == Status.MERGED }.count().toFloat() * mergeAllocTotal
                val mergedTotal = completedMerge * onePercentOfTotal
                (downloadedTotal + mergedTotal).roundToLong()
            }
            Phase.COMPLETED -> {
                total
            }
        }
    }

    private fun getOpeningRequest(): Downloader.Request {
        val headers = initialDownload.headers.toMutableMap()
        headers["Range"] = "bytes=0-"
        return Downloader.Request(
                id = initialDownload.id,
                url = initialDownload.url,
                headers = headers,
                file = initialDownload.file,
                tag = initialDownload.tag)
    }

    private fun getRequestForFileChunk(fileChunk: FileChuck): Downloader.Request {
        val headers = initialDownload.headers.toMutableMap()
        headers["Range"] = "bytes=${fileChunk.startBytes + fileChunk.downloaded}-"
        return Downloader.Request(
                id = initialDownload.id,
                url = initialDownload.url,
                headers = headers,
                file = initialDownload.file,
                tag = initialDownload.tag)
    }

    private fun getFileChunkList(openingResponseCode: Int, request: Downloader.Request): List<FileChuck> {
        return if (openingResponseCode == HttpURLConnection.HTTP_PARTIAL) {
            val fileChunkInfo = getChuckInfo(request)
            var counterBytes = 0L
            val fileChunks = mutableListOf<FileChuck>()
            for (position in 1..fileChunkInfo.chunks) {
                val startBytes = counterBytes
                val endBytes = if (fileChunkInfo.chunks == position) {
                    total
                } else {
                    counterBytes + fileChunkInfo.bytesPerChunk
                }
                counterBytes = endBytes
                val fileChunk = FileChuck(
                        id = downloadInfo.id,
                        position = position,
                        startBytes = startBytes,
                        endBytes = endBytes,
                        file = getFileForChunk(downloadInfo.id, position))
                fileChunks.add(fileChunk)
            }
            fileChunks.forEach {
                it.downloaded = getSavedDownloadedForFileChunk(it)
                downloaded += it.downloaded
                if (it.startBytes + it.downloaded == it.endBytes) {
                    it.status = Status.COMPLETED
                }
            }
            fileChunks
        } else {
            val singleFileChunk = FileChuck(
                    id = downloadInfo.id,
                    position = 1,
                    startBytes = 0,
                    endBytes = total,
                    file = getFileForChunk(downloadInfo.id, 1))
            singleFileChunk.downloaded = getSavedDownloadedForFileChunk(singleFileChunk)
            if (singleFileChunk.startBytes + singleFileChunk.downloaded == singleFileChunk.endBytes) {
                singleFileChunk.status = Status.COMPLETED
            }
            downloaded += singleFileChunk.downloaded
            listOf(singleFileChunk)
        }
    }

    private fun getChuckInfo(request: Downloader.Request): FileChunkInfo {
        val fileChunkLimit = downloader.getFileChunkSize(request, total) ?: DEFAULT_FILE_CHUNK_LIMIT
        return if (fileChunkLimit == DEFAULT_FILE_CHUNK_LIMIT) {
            val fileSizeInMb = total.toFloat() / (1024 * 1024).toFloat()
            val fileSizeInGb = total.toFloat() / (1024 * 1024 * 1024).toFloat()
            when {
                fileSizeInGb >= 1 -> {
                    val chunks = 4
                    val bytesPerChunk = ceil((total.toFloat() / chunks.toFloat())).toLong()
                    FileChunkInfo(chunks, bytesPerChunk)
                }
                fileSizeInMb >= 1 -> {
                    val chunks = 2
                    val bytesPerChunk = ceil((total.toFloat() / chunks.toFloat())).toLong()
                    FileChunkInfo(chunks, bytesPerChunk)
                }
                else -> FileChunkInfo(1, total)
            }
        } else {
            val bytesPerChunk = ceil((total.toFloat() / fileChunkLimit.toFloat())).toLong()
            return FileChunkInfo(fileChunkLimit, bytesPerChunk)
        }
    }

    private fun getFileForChunk(id: Int, position: Int): String {
        return "$fileChunkTempDir/$id/$id.$position.tmp"
    }

    private fun deleteTempDirForId(id: Int) {
        val file = File("$fileChunkTempDir/$id")
        if (file.exists()) {
            file.delete()
        }
    }

    private fun getSavedDownloadedForFileChunk(fileChunk: FileChuck): Long {
        var downloaded = 0L
        val file = File("${getFileForChunk(fileChunk.id, fileChunk.position)}.txt")
        if (file.exists() && !interrupted && !terminated) {
            val bufferedReader = BufferedReader(FileReader(file))
            try {
                val string: String? = bufferedReader.readLine()
                downloaded = string?.toLong() ?: 0L
            } catch (e: Exception) {
                logger.e("FileDownloader", e)
            } finally {
                try {
                    bufferedReader.close()
                } catch (e: Exception) {
                    logger.e("FileDownloader", e)
                }
            }
        }
        return downloaded
    }

    private fun saveDownloadedForFileChunk(fileChunk: FileChuck, downloaded: Long) {
        val file = getFile("${getFileForChunk(fileChunk.id, fileChunk.position)}.txt")
        if (file.exists() && !interrupted && !terminated) {
            val bufferedWriter = BufferedWriter(FileWriter(file))
            try {
                bufferedWriter.write(downloaded.toString())
            } catch (e: Exception) {
                logger.e("FileDownloader", e)
            } finally {
                try {
                    bufferedWriter.close()
                } catch (e: Exception) {
                    logger.e("FileDownloader", e)
                }
            }
        }
    }

    private fun getAverageDownloadedBytesPerSecond(): Long {
        if (averageDownloadedBytesPerSecond < 1) {
            return 0L
        }
        return ceil(averageDownloadedBytesPerSecond).toLong()
    }

    private fun waitAndPerformProgressReporting(chunksList: List<FileChuck>) {
        var reportingStopTime: Long
        var downloadSpeedStopTime: Long
        var downloadedBytesPerSecond = getProgressDownloaded()
        var reportingStartTime = System.nanoTime()
        var downloadSpeedStartTime = System.nanoTime()
        while (chunksMergingOrDownloading(chunksList) && !interrupted && !terminated) {
            downloadInfo.downloaded = getProgressDownloaded()
            downloadSpeedStopTime = System.nanoTime()
            val downloadSpeedCheckTimeElapsed = hasIntervalTimeElapsed(downloadSpeedStartTime,
                    downloadSpeedStopTime, DEFAULT_DOWNLOAD_SPEED_REPORTING_INTERVAL_IN_MILLISECONDS)

            if (downloadSpeedCheckTimeElapsed) {
                downloadedBytesPerSecond = getProgressDownloaded() - downloadedBytesPerSecond
                movingAverageCalculator.add(downloadedBytesPerSecond.toDouble())
                averageDownloadedBytesPerSecond =
                        movingAverageCalculator.getMovingAverageWithWeightOnRecentValues()
                estimatedTimeRemainingInMilliseconds = calculateEstimatedTimeRemainingInMilliseconds(
                        downloadedBytes = getProgressDownloaded(),
                        totalBytes = total,
                        downloadedBytesPerSecond = getAverageDownloadedBytesPerSecond())
                downloadedBytesPerSecond = getProgressDownloaded()
                if (progressReportingIntervalMillis > DEFAULT_DOWNLOAD_SPEED_REPORTING_INTERVAL_IN_MILLISECONDS) {
                    delegate?.saveDownloadProgress(downloadInfo)
                }
            }
            reportingStopTime = System.nanoTime()
            val hasReportingTimeElapsed = hasIntervalTimeElapsed(reportingStartTime,
                    reportingStopTime, progressReportingIntervalMillis)
            if (hasReportingTimeElapsed) {
                if (progressReportingIntervalMillis <= DEFAULT_DOWNLOAD_SPEED_REPORTING_INTERVAL_IN_MILLISECONDS) {
                    delegate?.saveDownloadProgress(downloadInfo)
                }
                if (!terminated) {
                    Log.d("tonyoTest", "progress ${calculateProgress(getProgressDownloaded(), total)}% Phase ${phase}")
                    delegate?.onProgress(
                            download = downloadInfo,
                            etaInMilliSeconds = estimatedTimeRemainingInMilliseconds,
                            downloadedBytesPerSecond = getAverageDownloadedBytesPerSecond())
                }
                reportingStartTime = System.nanoTime()
            }

            if (downloadSpeedCheckTimeElapsed) {
                downloadSpeedStartTime = System.nanoTime()
            }
        }
    }

    private fun mergeChunk(fileChunk: FileChuck,
                           outputStream: OutputStream?,
                           randomAccessFile: RandomAccessFile?) {
        val chunkFile = getFile(fileChunk.file)
        val request = getRequestForFileChunk(fileChunk)
        val inputStream = downloader.getRequestInputStream(request, 0)
        var inputRandomAccessFile: RandomAccessFile? = null
        if (inputStream == null) {
            inputRandomAccessFile = RandomAccessFile(chunkFile, "r")
        }
        try {
            val buffer = ByteArray(downloadBufferSizeBytes)
            var read = inputStream?.read(buffer, 0, downloadBufferSizeBytes)
                    ?: (inputRandomAccessFile?.read(buffer, 0, downloadBufferSizeBytes) ?: -1)
            while (read != -1 && !interrupted && !terminated) {
                outputStream?.write(buffer, 0, read)
                randomAccessFile?.write(buffer, 0, read)
                read = inputStream?.read(buffer, 0, downloadBufferSizeBytes) ?: (inputRandomAccessFile?.read(buffer, 0, downloadBufferSizeBytes)
                        ?: -1)
            }
        } catch (e: Exception) {
            logger.e("FileDownloader", e)
            throw e
        } finally {
            try {
                inputStream?.close()
            } catch (e: Exception) {
                logger.e("FileDownloader", e)
            }
            try {
                inputRandomAccessFile?.close()
            } catch (e: Exception) {
                logger.e("FileDownloader", e)
            }
        }
    }

    private fun downloadChunks(fileChunks: List<FileChuck>) {
        for (downloadChunk in fileChunks) {
            if (!interrupted && !terminated) {
                downloadChunk.status = Status.DOWNLOADING
                chunkExecutorService?.execute({
                    val downloadRequest = getRequestForFileChunk(downloadChunk)
                    var downloadResponse: Downloader.Response? = null
                    var outputStream: OutputStream? = null
                    var randomAccessFileOutput: RandomAccessFile? = null
                    try {
                        downloadResponse = downloader.execute(downloadRequest)
                        if (!terminated && !interrupted && downloadResponse?.isSuccessful == true) {
                            val file = getFile(downloadChunk.file)
                            val seekPosition = if (downloadResponse.code == HttpURLConnection.HTTP_PARTIAL) {
                                downloadChunk.downloaded
                            } else {
                                0
                            }
                            outputStream = downloader.getRequestOutputStream(downloadRequest, seekPosition)
                            if (outputStream == null) {
                                randomAccessFileOutput = RandomAccessFile(file, "rw")
                                randomAccessFileOutput.seek(seekPosition)
                            }
                            var reportingStopTime: Long
                            val buffer = ByteArray(downloadBufferSizeBytes)
                            var read: Int = downloadResponse.byteStream?.read(buffer, 0, downloadBufferSizeBytes)
                                    ?: -1
                            var remainderBytes: Long = downloadChunk.endBytes - (downloadChunk.startBytes + downloadChunk.downloaded)
                            var reportingStartTime = System.nanoTime()
                            while (remainderBytes > 0L && read != -1 && !interrupted && !terminated) {
                                if (read <= remainderBytes) {
                                    randomAccessFileOutput?.write(buffer, 0, read)
                                    outputStream?.write(buffer, 0, read)
                                    downloadChunk.downloaded += read
                                    addToTotalDownloaded(read)
                                    read = downloadResponse.byteStream?.read(buffer, 0, downloadBufferSizeBytes) ?: -1
                                    remainderBytes = downloadChunk.endBytes - (downloadChunk.startBytes + downloadChunk.downloaded)
                                } else {
                                    randomAccessFileOutput?.write(buffer, 0, remainderBytes.toInt())
                                    outputStream?.write(buffer, 0, remainderBytes.toInt())
                                    downloadChunk.downloaded += remainderBytes
                                    addToTotalDownloaded(remainderBytes.toInt())
                                    read = -1
                                }

                                reportingStopTime = System.nanoTime()
                                val hasReportingTimeElapsed = hasIntervalTimeElapsed(reportingStartTime,
                                        reportingStopTime, progressReportingIntervalMillis)
                                if (hasReportingTimeElapsed) {
                                    saveDownloadedForFileChunk(downloadChunk, downloadChunk.downloaded)
                                    reportingStartTime = System.nanoTime()
                                }
                            }
                            if (remainderBytes == 0L) {
                                downloadChunk.status = Status.COMPLETED
                            } else {
                                downloadChunk.status = Status.QUEUED
                            }
                        } else if (downloadResponse == null && !interrupted && !terminated) {
                            throw FetchException(EMPTY_RESPONSE_BODY,
                                    FetchException.Code.EMPTY_RESPONSE_BODY)
                        } else if (downloadResponse?.isSuccessful == false && !interrupted && !terminated) {
                            throw FetchException(RESPONSE_NOT_SUCCESSFUL,
                                    FetchException.Code.REQUEST_NOT_SUCCESSFUL)
                        } else if (!interrupted && !terminated) {
                            throw FetchException(UNKNOWN_ERROR,
                                    FetchException.Code.UNKNOWN)
                        }
                    } catch (e: Exception) {
                        downloadChunk.status = Status.ERROR
                        downloadChunk.errorException = e
                    } finally {
                        try {
                            if (downloadResponse != null) {
                                downloader.disconnect(downloadResponse)
                            }
                        } catch (e: Exception) {
                            logger.e("FileDownloader", e)
                        }
                        try {
                            randomAccessFileOutput?.close()
                        } catch (e: Exception) {
                            logger.e("FileDownloader", e)
                        }
                        try {
                            outputStream?.close()
                        } catch (e: Exception) {
                            logger.e("FileDownloader", e)
                        }
                    }
                })
            }
        }
    }

    private fun addToTotalDownloaded(read: Int) {
        synchronized(downloadedLock) {
            downloaded += read
        }
    }

    private fun chunksMergingOrDownloading(chunkDownloadsList: List<FileChuck>): Boolean {
        if (!interrupted && !terminated) {
            for (chunk in chunkDownloadsList) {
                if (chunk.status == Status.DOWNLOADING || chunk.status == Status.MERGING) {
                    return true
                }
            }
        }
        return false
    }

    private fun getFile(filePath: String): File {
        val file = File(filePath)
        if (!file.exists()) {
            if (file.parentFile != null && !file.parentFile.exists()) {
                if (file.parentFile.mkdirs()) {
                    file.createNewFile()
                    logger.d("FileDownloader download file ${file.absolutePath} created")
                }
            } else {
                file.createNewFile()
                logger.d("FileDownloader download file ${file.absolutePath} created")
            }
        }
        return file
    }

    data class FileChunkInfo(val chunks: Int, val bytesPerChunk: Long)

    data class FileChuck(val id: Int = 0,
                         val position: Int = 0,
                         val startBytes: Long = 0L,
                         val endBytes: Long = 0L,
                         @Volatile
                         var downloaded: Long = 0L,
                         var file: String,
                         @Volatile
                         var status: Status = Status.QUEUED,
                         var errorException: Throwable? = null) {

        val completed: Boolean
            get() {
                return status == Status.COMPLETED
            }
    }

    enum class Status {
        QUEUED,
        DOWNLOADING,
        COMPLETED,
        ERROR,
        MERGING,
        MERGED;
    }

    enum class Phase {
        DOWNLOADING,
        MERGING,
        COMPLETED;
    }

}