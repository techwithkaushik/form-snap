package org.techwithkaushik.formsnap.viewmodel

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.techwithkaushik.formsnap.processor.ImageProcessor

data class FormSnapUiState(
    val processing: Boolean = false,
    val result: ByteArray? = null,
    val error: String? = null,
)

class FormSnapViewModel(
    private val processor: ImageProcessor = ImageProcessor(),
) {
    private val supervisorJob = SupervisorJob()
    private val processingJob = supervisorJob

    private val _state = MutableStateFlow(FormSnapUiState())
    val state: StateFlow<FormSnapUiState> = _state.asStateFlow()

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        _state.value = _state.value.copy(
            processing = false,
            error = throwable.message ?: "Image processing failed.",
        )
    }

    fun processForm(
        imageData: ByteArray,
        adaptiveBlockSize: Int = 31,
        adaptiveConstant: Double = 8.0,
    ) {
        processingJob.cancelChildren()
        _state.value = FormSnapUiState(processing = true)

        kotlinx.coroutines.CoroutineScope(
            supervisorJob + Dispatchers.Default + exceptionHandler,
        ).launch {
            val input = imageData.copyOf()
            val result = withContext(Dispatchers.Default) {
                processor.processForm(
                    imageData = input,
                    adaptiveBlockSize = adaptiveBlockSize,
                    adaptiveConstant = adaptiveConstant,
                )
            }
            _state.value = FormSnapUiState(
                processing = false,
                result = result,
                error = null,
            )
        }
    }

    fun clearResult() {
        processingJob.cancelChildren()
        _state.value = FormSnapUiState()
    }

    fun close() {
        supervisorJob.cancel()
    }
}
