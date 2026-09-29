package org.techwithkaushik.formsnap.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.techwithkaushik.formsnap.processor.ImageProcessor

data class FormSnapUiState(
    val processing: Boolean = false,
    val result: ByteArray? = null,
    val error: String? = null,
)

class FormSnapViewModel(
    private val processor: ImageProcessor,
) : ViewModel() {

    private val processingSupervisor = SupervisorJob(viewModelScope.coroutineContext[Job])

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        _state.value = _state.value.copy(
            processing = false,
            error = throwable.message ?: "Image processing failed.",
        )
    }

    private val processingScope = viewModelScope +
        processingSupervisor +
        Dispatchers.Default +
        exceptionHandler

    private var processingJob: Job? = null

    private val _state = MutableStateFlow(FormSnapUiState())
    val state: StateFlow<FormSnapUiState> = _state.asStateFlow()

    fun processForm(
        imageData: ByteArray,
        adaptiveBlockSize: Int = 31,
        adaptiveConstant: Double = 8.0,
    ) {
        require(imageData.isNotEmpty()) {
            "Image data is empty."
        }

        processingJob?.cancel()

        val input = imageData.copyOf()

        _state.value = FormSnapUiState(
            processing = true,
            result = null,
            error = null,
        )

        processingJob = processingScope.launch {
            val result = processor.processForm(
                imageData = input,
                adaptiveBlockSize = adaptiveBlockSize,
                adaptiveConstant = adaptiveConstant,
            )

            _state.value = FormSnapUiState(
                processing = false,
                result = result,
                error = null,
            )
        }
    }

    fun cancelProcessing() {
        processingJob?.cancel()
        processingJob = null
        _state.value = _state.value.copy(
            processing = false,
        )
    }

    fun clearResult() {
        processingJob?.cancel()
        processingJob = null
        _state.value = FormSnapUiState()
    }

    override fun onCleared() {
        processingJob?.cancel()
        processingJob = null
        processingSupervisor.cancel()
        super.onCleared()
    }
}
