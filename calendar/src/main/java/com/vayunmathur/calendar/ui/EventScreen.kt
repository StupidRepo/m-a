package com.vayunmathur.calendar.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventScreen(viewModel: CalendarViewModel, instance: Instance, backStack: NavBackStack<Route>) {
    val events by viewModel.events.collectAsState()
    val calendars by viewModel.calendars.collectAsState()

    val event = events.find { it.id == instance.eventID }
    if (event == null) {
        // simple empty state
        Text(stringResource(R.string.event_not_found))
        return
    }

    val calendar = calendars.find { it.id == event.calendarID }!!

    val context = LocalContext.current

    val isEditable = calendar.canModify

    Scaffold(topBar = {
        TopAppBar({}, navigationIcon = {
            IconNavigation(backStack)
        }, actions = {
            if(isEditable) {
                IconButton({
                    backStack.add(Route.EditEvent(event.id))
                }) {
                    IconEdit()
                }
                IconButton({
                    viewModel.deleteEvent(event.id!!)
                    backStack.pop()
                }) {
                    IconDelete()
                }
            }
        })
    }) { paddingValues ->
        Column(Modifier.padding(paddingValues)) {
            ListItem({
                Text(event.title, style = MaterialTheme.typography.titleLarge)
            }, supportingContent = {
                Column {
                    Text(calendar.displayName)
                    Text(dateRangeString(context,instance.startDateTimeDisplay.date, instance.endDateTimeDisplay.date, instance.startDateTimeDisplay.time, instance.endDateTimeDisplay.time, instance.allDay))
                    instance.rrule?.let { Text(it.toString()) }
                }
            }, leadingContent = {
                Box(Modifier.size(24.dp).background(Color(calendar.color), RoundedCornerShape(4.dp)))
            })
            if(event.description.isNotBlank()) ListItem({
                Text(event.description)
            }, leadingContent = {
                Icon(painterResource(R.drawable.description_24px), null)
            })
            if(event.location.isNotBlank()) ListItem({Text(event.location)}, leadingContent =
                {Icon(painterResource(R.drawable.globe_24px), null)},
            )
        }
    }
}

fun dateRangeString(context: Context, startDate: LocalDate, endDate: LocalDate, startTime: LocalTime, endTime: LocalTime, allDay: Boolean, includeDate: Boolean = true): String {
    return if(allDay) {
        if(startDate.toEpochDays() + 1 == endDate.toEpochDays()) {
            if (includeDate) startDate.format(dateFormat) else context.getString(R.string.all_day)
        } else {
            context.getString(R.string.date_range_format, startDate.format(dateFormat), endDate.format(dateFormat))
        }
    } else {
        val timeFmt = if(DateFormat.is24HourFormat(context)) timeFormat24 else timeFormat12
        if(startDate == endDate) {
            if (includeDate) {
                context.getString(R.string.date_time_range_format, startDate.format(dateFormat), startTime.format(timeFmt), endTime.format(timeFmt))
            } else {
                context.getString(R.string.date_range_format, startTime.format(timeFmt), endTime.format(timeFmt))
            }
        } else {
            context.getString(R.string.full_date_time_range_format, startDate.format(dateFormat), startTime.format(timeFmt), endDate.format(dateFormat), endTime.format(timeFmt))
        }
    }
}
