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
import kotlinx.coroutines.withContext
import org.techwithkaushik.formsnap.processor.ImageProcessor

data class FormSnapUiState(
    val processing: Boolean = false,
    val result: ByteArray? = null,
    val error: String? = null,
)

class FormSnapViewModel : ViewModel() {
    private val processingJob = SupervisorJob(viewModelScope.coroutineContext[Job])

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        _state.value = _state.value.copy(
            processing = false,
            error = throwable.message ?: "Image processing failed.",
        )
    }

    private val _state = MutableStateFlow(FormSnapUiState())
    val state: StateFlow<FormSnapUiState> = _state.asStateFlow()

    private val processor = ImageProcessor()

    fun processForm(
        imageData: ByteArray,
        adaptiveBlockSize: Int = 31,
        adaptiveConstant: Double = 8.0,
    ) {
        processingJob.cancelChildren()
        _state.value = FormSnapUiState(processing = true)

        viewModelScope.launch(
            context = processingJob + Dispatchers.Default + exceptionHandler,
        ) {
            val result = withContext(Dispatchers.Default) {
                processor.processForm(
                    imageData = imageData,
                    adaptiveBlockSize = adaptiveBlockSize,
                    adaptiveConstant = adaptiveConstant,
                )
            }
            _state.value = FormSnapUiState(
                processing = false,
                result = result,
            )
        }
    }

    override fun onCleared() {
        processingJob.cancel()
        super.onCleared()
    }
}
