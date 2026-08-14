# v5.1.4 参考方案吸收记录

参考方案只作为实验设计与公开实现证据，不覆盖本项目架构。

吸收：

```text
A = VK + Set-1 scan
B = same VK + scan=0
mappedScan 与 wireScan 分离
held key 时禁止模式切换
A -> B -> A 复验
保留 v5.1.3 Input Forensics
raw handshake 继续记录
```

没有直接照搬：

```text
OpenNOW batching
send-time timestamp restamp
OpenNOW 其他 HID/device negotiation
CloudNow 平台输入层
```

原因：本轮必须保持单变量，只测试最终 keyboard `wireScan`。

公开实现冲突只作为实验依据：CloudNow 当前仍发送非零 Set-1 scan；OpenNOW 当前普通键路径使用 position-derived VK + scan=0。因此本版不把任一实现当作 NVIDIA 官方唯一语义。

**已验证无误。**
