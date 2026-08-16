"""Net1D from PKUDigitalHealth/ECGFounder (MIT). Kept local for ONNX export."""

import torch
import torch.nn as nn
import torch.nn.functional as F


class MyConv1dPadSame(nn.Module):
    def __init__(self, in_channels, out_channels, kernel_size, stride, groups=1):
        super().__init__()
        self.in_channels = in_channels
        self.out_channels = out_channels
        self.kernel_size = kernel_size
        self.stride = stride
        self.groups = groups
        self.conv = nn.Conv1d(
            in_channels=in_channels,
            out_channels=out_channels,
            kernel_size=kernel_size,
            stride=stride,
            groups=groups,
        )

    def forward(self, x):
        in_dim = x.shape[-1]
        out_dim = (in_dim + self.stride - 1) // self.stride
        p = max(0, (out_dim - 1) * self.stride + self.kernel_size - in_dim)
        pad_left = p // 2
        pad_right = p - pad_left
        return self.conv(F.pad(x, (pad_left, pad_right), "constant", 0))


class MyMaxPool1dPadSame(nn.Module):
    def __init__(self, kernel_size):
        super().__init__()
        self.kernel_size = kernel_size
        self.max_pool = nn.MaxPool1d(kernel_size=kernel_size)

    def forward(self, x):
        p = max(0, self.kernel_size - 1)
        pad_left = p // 2
        pad_right = p - pad_left
        return self.max_pool(F.pad(x, (pad_left, pad_right), "constant", 0))


class Swish(nn.Module):
    def forward(self, x):
        return x * torch.sigmoid(x)


class BasicBlock(nn.Module):
    def __init__(
        self,
        in_channels,
        out_channels,
        ratio,
        kernel_size,
        stride,
        groups,
        downsample,
        is_first_block=False,
        use_bn=True,
        use_do=True,
    ):
        super().__init__()
        self.in_channels = in_channels
        self.out_channels = out_channels
        self.downsample = downsample
        self.stride = stride if downsample else 1
        self.is_first_block = is_first_block
        self.use_bn = use_bn
        self.use_do = use_do
        self.middle_channels = int(out_channels * ratio)

        self.bn1 = nn.BatchNorm1d(in_channels)
        self.activation1 = Swish()
        self.do1 = nn.Dropout(p=0.5)
        self.conv1 = MyConv1dPadSame(in_channels, self.middle_channels, 1, 1, 1)

        self.bn2 = nn.BatchNorm1d(self.middle_channels)
        self.activation2 = Swish()
        self.do2 = nn.Dropout(p=0.5)
        self.conv2 = MyConv1dPadSame(
            self.middle_channels, self.middle_channels, kernel_size, self.stride, groups
        )

        self.bn3 = nn.BatchNorm1d(self.middle_channels)
        self.activation3 = Swish()
        self.do3 = nn.Dropout(p=0.5)
        self.conv3 = MyConv1dPadSame(self.middle_channels, out_channels, 1, 1, 1)

        r = 2
        self.se_fc1 = nn.Linear(out_channels, out_channels // r)
        self.se_fc2 = nn.Linear(out_channels // r, out_channels)
        self.se_activation = Swish()
        if downsample:
            self.max_pool = MyMaxPool1dPadSame(kernel_size=self.stride)

    def forward(self, x):
        identity = x
        out = x
        if not self.is_first_block:
            if self.use_bn:
                out = self.bn1(out)
            out = self.activation1(out)
            if self.use_do:
                out = self.do1(out)
        out = self.conv1(out)
        if self.use_bn:
            out = self.bn2(out)
        out = self.activation2(out)
        if self.use_do:
            out = self.do2(out)
        out = self.conv2(out)
        if self.use_bn:
            out = self.bn3(out)
        out = self.activation3(out)
        if self.use_do:
            out = self.do3(out)
        out = self.conv3(out)

        se = out.mean(-1)
        se = torch.sigmoid(self.se_fc2(self.se_activation(self.se_fc1(se))))
        out = torch.einsum("abc,ab->abc", out, se)

        if self.downsample:
            identity = self.max_pool(identity)
        if self.out_channels != self.in_channels:
            identity = identity.transpose(-1, -2)
            ch1 = (self.out_channels - self.in_channels) // 2
            ch2 = self.out_channels - self.in_channels - ch1
            identity = F.pad(identity, (ch1, ch2), "constant", 0)
            identity = identity.transpose(-1, -2)
        return out + identity


class BasicStage(nn.Module):
    def __init__(
        self,
        in_channels,
        out_channels,
        ratio,
        kernel_size,
        stride,
        groups,
        i_stage,
        m_blocks,
        use_bn=True,
        use_do=True,
    ):
        super().__init__()
        self.block_list = nn.ModuleList()
        for i_block in range(m_blocks):
            is_first = i_stage == 0 and i_block == 0
            downsample = i_block == 0
            tmp_in = in_channels if i_block == 0 else out_channels
            self.block_list.append(
                BasicBlock(
                    in_channels=tmp_in,
                    out_channels=out_channels,
                    ratio=ratio,
                    kernel_size=kernel_size,
                    stride=stride if downsample else 1,
                    groups=groups,
                    downsample=downsample,
                    is_first_block=is_first,
                    use_bn=use_bn,
                    use_do=use_do,
                )
            )

    def forward(self, x):
        for block in self.block_list:
            x = block(x)
        return x


class Net1D(nn.Module):
    def __init__(
        self,
        in_channels,
        base_filters,
        ratio,
        filter_list,
        m_blocks_list,
        kernel_size,
        stride,
        groups_width,
        n_classes,
        use_bn=True,
        use_do=True,
    ):
        super().__init__()
        self.first_conv = MyConv1dPadSame(in_channels, base_filters, kernel_size, 2)
        self.first_bn = nn.BatchNorm1d(base_filters)
        self.first_activation = Swish()
        self.use_bn = use_bn
        self.stage_list = nn.ModuleList()
        channels = base_filters
        for i_stage, out_channels in enumerate(filter_list):
            self.stage_list.append(
                BasicStage(
                    in_channels=channels,
                    out_channels=out_channels,
                    ratio=ratio,
                    kernel_size=kernel_size,
                    stride=stride,
                    groups=out_channels // groups_width,
                    i_stage=i_stage,
                    m_blocks=m_blocks_list[i_stage],
                    use_bn=use_bn,
                    use_do=use_do,
                )
            )
            channels = out_channels
        self.dense = nn.Linear(channels, n_classes)

    def forward(self, x):
        out = self.first_conv(x)
        if self.use_bn:
            out = self.first_bn(out)
        out = self.first_activation(out)
        for stage in self.stage_list:
            out = stage(out)
        return self.dense(out.mean(-1))
