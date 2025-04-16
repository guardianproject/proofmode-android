package org.witness.proofmode.camera.fragments

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class CountDownState {
    object Idle:CountDownState()
    object  Running:CountDownState()
    object Cancelled:CountDownState()
    object Completed:CountDownState()
}

@Composable
fun CountDownTimerUI(
    modifier: Modifier = Modifier,
    initialDelay:Int,
    countDownState: CountDownState,
    onStateChange: (CountDownState) -> Unit,
    onCountDownCompleted: () -> Unit
){
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var timeLeft by remember { mutableIntStateOf(initialDelay) }

    LaunchedEffect(countDownState) {

        when(countDownState){
            is CountDownState.Running -> {
                job?.cancel()
                timeLeft = initialDelay
                job = scope.launch {
                    for (second in initialDelay downTo 1){
                        timeLeft = second
                        delay(1000)
                    }
                    onStateChange(CountDownState.Completed)
                    onCountDownCompleted()
                }
            }
            is CountDownState.Cancelled-> {
                job?.cancel()
                onStateChange(CountDownState.Idle)
            }
            else -> null
        }
    }
    if (countDownState == CountDownState.Running) {
        Box(modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ){
            Text(text = timeLeft.toString(),
                style = TextStyle(fontWeight = FontWeight.ExtraBold,
                    fontSize = 100.sp,
                    color = Color.White)
            )
        }
    }
}