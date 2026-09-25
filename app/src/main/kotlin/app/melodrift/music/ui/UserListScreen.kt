package app.melodrift.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melodrift.music.R
import app.melodrift.music.net.NcmApi
import app.melodrift.music.net.ioNet

/*
 * 关注 / 粉丝列表：从个人主页统计区点「关注 / 粉丝」进入。
 * 行 = 头像 + 昵称 + 签名，点击进入对方个人主页。
 */

@Composable
fun UserListScreen(
    uid: Long,
    title: String,
    fans: Boolean,
    onBack: () -> Unit,
    onOpenUser: (Long, String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = dimensionResource(R.dimen.page_padding),
                    end = dimensionResource(R.dimen.icon_button_touch_min),
                    top = 4.dp
                )
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Box(Modifier.weight(1f)) {
            AsyncContent(
                load = {
                    ioNet {
                        if (fans) NcmApi.followerUsers(uid, 100, 0)
                        else NcmApi.followedUsers(uid, 100, 0)
                    }.orEmpty()
                },
                retryKey = "$uid-$fans"
            ) { users ->
                if (users.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(
                                if (fans) R.string.user_no_followers else R.string.user_no_following
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = dimensionResource(R.dimen.page_padding),
                            end = dimensionResource(R.dimen.page_padding),
                            top = dimensionResource(R.dimen.space_s),
                            bottom = dimensionResource(R.dimen.list_bottom_padding)
                        )
                    ) {
                        items(users, key = { it.userId }) { u ->
                            FollowRow(u) { onOpenUser(u.userId, u.nickname) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowRow(u: NcmApi.FollowUser, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = dimensionResource(R.dimen.space_s))
    ) {
        Avatar(
            url = u.avatarUrl?.let { addParam(it, "100y100") },
            modifier = Modifier.size(dimensionResource(R.dimen.user_cover))
        )
        Spacer(Modifier.width(dimensionResource(R.dimen.space_m)))
        Column(Modifier.weight(1f)) {
            Text(
                u.nickname.ifBlank { stringResource(R.string.comments_anonymous) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (u.signature.isNotBlank()) {
                Text(
                    u.signature,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}