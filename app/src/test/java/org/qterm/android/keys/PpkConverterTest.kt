package org.qterm.android.keys

import com.trilead.ssh2.crypto.PEMDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.qterm.android.vault.SSHKey

/**
 * PPK2/PPK3 → OpenSSH: эталон — puttygen 0.81 (-O private-openssh-new) с тем же checkint,
 * поэтому сверка байт-в-байт. Argon2 подставной: таблица из argon2-cffi.
 */
class PpkConverterTest {

    private class Vec(val name: String, val pass: String, val check: Int, val ppk: String, val opensshB64: String, val pub: String)

    // puttygen 0.81: ppk, ожидаемый OpenSSH (puttygen -O private-openssh-new), публичный (-O public-openssh)
    private val vectors = listOf(
        Vec("ed3enc", "test-pass", -659521783, """PuTTY-User-Key-File-3: ssh-ed25519
Encryption: aes256-cbc
Comment: ed-ppk3
Public-Lines: 2
AAAAC3NzaC1lZDI1NTE5AAAAIECMbida7kToIETaGx46CT9hPwfVkwd5/EmOc9A2
cmcr
Key-Derivation: Argon2id
Argon2-Memory: 8192
Argon2-Passes: 1
Argon2-Parallelism: 1
Argon2-Salt: 0423d4edc7a0967cc14f085453a5605d
Private-Lines: 1
pGKh9UUiG42Q4jLGSej1ycMcZQ+eWEC2cofC37bXryTn0DdL1MLwk8oTZcRO59AG
Private-MAC: c34d5a95794c6dbb1b2c137ad6f80ec1062b3ac72c27f91727199e9b78e34bd8
""", "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZWQyNTUxOQAAACBAjG4nWu5E6CBE2hseOgk/YT8H1ZMHefxJjnPQNnJnKwAAAJDYsH8J2LB/CQAAAAtzc2gtZWQyNTUxOQAAACBAjG4nWu5E6CBE2hseOgk/YT8H1ZMHefxJjnPQNnJnKwAAAEA/soaB4WIwfnMcv9rlb/jKAgIj3Vr48dhQ9XI21vrHYECMbida7kToIETaGx46CT9hPwfVkwd5/EmOc9A2cmcrAAAAB2VkLXBwazMBAgMEBQY=", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIECMbida7kToIETaGx46CT9hPwfVkwd5/EmOc9A2cmcr ed-ppk3"),
        Vec("rsa2enc", "Пароль-2", -833748991, """PuTTY-User-Key-File-2: ssh-rsa
Encryption: aes256-cbc
Comment: rsa-ppk2
Public-Lines: 6
AAAAB3NzaC1yc2EAAAADAQABAAABAQCFOEUNqgVToMnbDcZIozPK2WuvJQ+IJIGz
jY511rhQzuVbFUshdfYmEBGFiTTfiR3XLE77UdvwsjTAm97/cPc0vkWMSTtXNUKZ
Zru/duWC7+snsnlNJan4n5bBEFvbbA6uMp0y9D2ftFi/rANGTut0Q/nk5bzwKu80
UDDSrQGSIsiyE4Ix1ha8U5TXef1ij5S3ePitNZpQYA/ZqOExrz9N9tPcF2Jz4cB8
A3R6NjjCluDu5tdSfckeCyan/rB26wBMvbyWaImVK99qmopB2EsKEW433bctilTC
CxEBfQT89I9OFahVMgWCL50sin7AsUxUePSBPJGQn1XERuTZS7Ln
Private-Lines: 14
ShbLNWpK9NkbUjGbtbv3YoErlhNForqddNmLbW49I5iPkypKqlLhtYTyOKAMqltb
68HMkQ6HqjAFkit0Kc+tqsx21aYQpbcVJQLBUkQ6dAauTmb8XoQeVHNgmTiLhu8w
6UlpokrPf4o4ZxuYQWcD0Bc4dOYcN9+bfXbDHhQ0APijTOzPLcnpqLA2xZ1fvd/l
sI1nY2/qRtwYU2QL5R3r9p+tYISrDRARyZqdWjY1a8+22Q9kjf+MUcl+H5XoxDxc
c05jQH9hpCRZB6Fz5wGHH1+kPm6M17/vdY2vGCWtRSlQDpG6sU5+ka/puww6rR9A
84pbJaV17ikoiFVWDqV8ZHdphRBMlO0ZpYM2kCmi33JhbRWNdUXO1JEKgk4/hWDQ
QnX8LGk+BNuobnusXj92I450EnCS081uOZPFsvAmJwvpCRdDjdDTAhcUqBDAhtF4
J9Q17g0uRi8BnKe+38dTWO8xD8CwwOTL6+AHERl9tbBBX7f6k0ine4lIpXjdodtz
TbHKpfvzXE0gV7YGrIwzin3leYoX0U0O21+6u8rx3CTViVoXFzdUmeRzbiVeP8Yx
92j5xURfzmoYalx0FzFxFVAB7a4oze9sFBoz7cVvQyOy8lsmt3ko9jAIWmDu2p7q
bm3l+NeOjdo6izApC0tqxKppR/synlLAfTG3ZjocKq9+iOY+GqZhKvuKCHheVXqw
1DpvSTWFF+GCFwZJNjSd1WW7ZMqCrV3zTOVIQFpoQcVsagg2NvwEhbarczNw7wkW
zay3LZjZRe+dcNlwIH441t2CJuUaLXr8Dd4zyzIOuY+EIOZJ5TlC3EKbhHZexhMv
STr4dPp9BHk/lt33prnjfvVvy8/wXIiwOyJ4D4iRkW7QQcNTLpDXt/vtxR3d2EWR
Private-MAC: 2bf254e0bdab8c0ab7da3efba415df5a38d6d790
""", "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAABFwAAAAdzc2gtcnNhAAAAAwEAAQAAAQEAhThFDaoFU6DJ2w3GSKMzytlrryUPiCSBs42Odda4UM7lWxVLIXX2JhARhYk034kd1yxO+1Hb8LI0wJve/3D3NL5FjEk7VzVCmWa7v3blgu/rJ7J5TSWp+J+WwRBb22wOrjKdMvQ9n7RYv6wDRk7rdEP55OW88CrvNFAw0q0BkiLIshOCMdYWvFOU13n9Yo+Ut3j4rTWaUGAP2ajhMa8/TfbT3Bdic+HAfAN0ejY4wpbg7ubXUn3JHgsmp/6wdusATL28lmiJlSvfapqKQdhLChFuN923LYpUwgsRAX0E/PSPThWoVTIFgi+dLIp+wLFMVHj0gTyRkJ9VxEbk2Uuy5wAAA8DOTgABzk4AAQAAAAdzc2gtcnNhAAABAQCFOEUNqgVToMnbDcZIozPK2WuvJQ+IJIGzjY511rhQzuVbFUshdfYmEBGFiTTfiR3XLE77UdvwsjTAm97/cPc0vkWMSTtXNUKZZru/duWC7+snsnlNJan4n5bBEFvbbA6uMp0y9D2ftFi/rANGTut0Q/nk5bzwKu80UDDSrQGSIsiyE4Ix1ha8U5TXef1ij5S3ePitNZpQYA/ZqOExrz9N9tPcF2Jz4cB8A3R6NjjCluDu5tdSfckeCyan/rB26wBMvbyWaImVK99qmopB2EsKEW433bctilTCCxEBfQT89I9OFahVMgWCL50sin7AsUxUePSBPJGQn1XERuTZS7LnAAAAAwEAAQAAAQB/Eh1L21EZY9UmzQzFXGaapqjAd2lJQwk0a2HnpNhKvA6pwrXXRN11kP4lkaqNTgzYobr0njBcdp862XLr1KT3+kX8eh3QIU1VZVjQWGizbIWxZ9usarCmJhN2NFRqB7g5EK5BXBcrrqq4qSq2dbDBRdWnMDv4GfvwukzXp45IBN9yKIkkdRh+UOL8Kxr7cP8p79fj1XgtaSlCxu4WJ89HfM6tN0culHkuv8BYUKHrIIMgu/CY7M3O8mjs/5SKvWLwigZDa43MIv4vDj4I8o5Be7kBEXljLYWvU9lNuSK/XwIXQ2twUh/fTEu5kAnNUmfIDlRDWG/d+QbY/pUubbuBAAAAgGsB3KjzOpjXQhEDQhls/6CnQVBvJPzjjqnjdS/NoB4d/0NLtypxwH01JUP+prKYefJ+27O5b50/Be9AZqcJctW3Op4lr1EtQHBH7E5YSWO0xhRVBgix/kPhGj3UvxktH3C2ltJFPsMkWD+5e5GWtfVSbOYQpUvT4XgbO1cmsoyXAAAAgQDeL1COlkSKgECmn21CqsxSkQM6RF6pycb/xa+xYhi3rWkWtJ7+3hFh2dHJSjWw2k7Vtq3nEg867o/9G5JkHto/+fUfg8939vFcJd+npUDvvpdoeRkpwNkSwONKnZiPu6JeIEaitsUWHIVbIL5rdmK12X6Y5/oByylgBMYWqjI1+wAAAIEAmX67XsA0DvCeOl6FEQ0GcSa54XMSc37LjdWHXnm6ahQ38YgokD+JkpSYFeRiUMEF31BWvwYPpmGKRBaC9ogWEXMqJmuSP9ibUGn2QubTrJfFRBIzoyjIJ5yJsnbzIiL5/dkyBEsGMZHouOGu1o+UrU4iieq4GPN8wTCjzWjx3wUAAAAIcnNhLXBwazIBAgM=", "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCFOEUNqgVToMnbDcZIozPK2WuvJQ+IJIGzjY511rhQzuVbFUshdfYmEBGFiTTfiR3XLE77UdvwsjTAm97/cPc0vkWMSTtXNUKZZru/duWC7+snsnlNJan4n5bBEFvbbA6uMp0y9D2ftFi/rANGTut0Q/nk5bzwKu80UDDSrQGSIsiyE4Ix1ha8U5TXef1ij5S3ePitNZpQYA/ZqOExrz9N9tPcF2Jz4cB8A3R6NjjCluDu5tdSfckeCyan/rB26wBMvbyWaImVK99qmopB2EsKEW433bctilTCCxEBfQT89I9OFahVMgWCL50sin7AsUxUePSBPJGQn1XERuTZS7Ln rsa-ppk2"),
        Vec("ed3plain", "", -1338796664, """PuTTY-User-Key-File-3: ssh-ed25519
Encryption: none
Comment: ed-ppk3-plain
Public-Lines: 2
AAAAC3NzaC1lZDI1NTE5AAAAIJURJL6xniHqPOgoIeB8k7+K7Ja4CJLdTa1XmGf8
Mr5e
Private-Lines: 1
AAAAIDj2rjOcL6oJn9y9lPWxAdDU1dgGqGbcGUX60lCxrQ9p
Private-MAC: 57c36bf668d7d37578f51d000ce367f46568926fe74be80b7cd20b682a2c2fef
""", "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZWQyNTUxOQAAACCVESS+sZ4h6jzoKCHgfJO/iuyWuAiS3U2tV5hn/DK+XgAAAKCwM5WIsDOViAAAAAtzc2gtZWQyNTUxOQAAACCVESS+sZ4h6jzoKCHgfJO/iuyWuAiS3U2tV5hn/DK+XgAAAEA49q4znC+qCZ/cvZT1sQHQ1NXYBqhm3BlF+tJQsa0PaZURJL6xniHqPOgoIeB8k7+K7Ja4CJLdTa1XmGf8Mr5eAAAADWVkLXBwazMtcGxhaW4BAgMEBQYHCAkKCwwNDg8Q", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJURJL6xniHqPOgoIeB8k7+K7Ja4CJLdTa1XmGf8Mr5e ed-ppk3-plain"),
        Vec("rsa3plain", "", -796779301, """PuTTY-User-Key-File-3: ssh-rsa
Encryption: none
Comment: rsa-ppk3-plain
Public-Lines: 6
AAAAB3NzaC1yc2EAAAADAQABAAABAQCNAH198NwsP6nXpDK2qBP1L5EuJwPJT+3o
PzNvtegi9WvYx0iiCyTh3YKoSEIp69i1zkSGYrpvOXoxFb6skq9VGP0aAC5smhXN
V0zNlGktZVIuh9yRAbNC80y+x6VtT5v7MZykGF1x5sNYjDHH7Tgex2htxKW+2Vv4
KnvhctprwUVU/dwnhCjZkXEatyLDGIr0yv3H0w1C80sUSvdRvoZbdT/E0TQ+CXfZ
slHTDWRX/BFiT+unws8b2bec2r+EMWwwVsigQ/2IEJHJx/Kv4TuHuzKaX9mHH7wt
y5BQGdJvMU9TLA5hm/BV6CK6kOD8tX/CbXaBLb1TBYr/HzczPs0j
Private-Lines: 14
AAABAHFWjojYPbJXricG9ji6W+h+xr0JYVLFK9++RZMq7iCDk5/ZeDnAqXHggvDP
vFdEG5rv5Ub8Pw2+bsjbyLug4MyPXIs00Bw9sv6uiK7K98wkoG2EibgJXNWZCfxk
MEXwctn0JAwHBb3TlvwrHKyusqiS8w8gvXXt865EP/WVQ5rOttO+xwyF/3dyW4Z3
zKKiBopX77lXXBKD/g8WGKyyg+1vp8DBb+fyi8nunXQp7AtyinU4mUchv5GpfChF
YXUfap+1q9FShu+Iho+wMIOi9Y2WdFp1ZwY5YmO7MTxRo0I4/P47XK9TetWPW777
7/aI3K96kamhrRBZoHLByoBgOiEAAACBAOJRdE5O9KKMVDL9PcS0Yi/0JnVq11XO
0M+S7FWC/D1NVOiqxE3hjUM0yjjZHidC5GLT/nAjXYinPO/JJ+ohYWajdO7ZRpwl
QvJU53fO1AC8Mhb7RP0cyReEq0lN9Sqf+PsO6imJ9aZXxeqYQOBVvcAtLxcFy/h/
PHvrdalQtYzxAAAAgQCffpM8RpZMicCJALQcKexJZi/hyeMRDRTMwMM98YTVGZjZ
JKrw9U7whkeCbmEC1AxpsP8L2qYeXJTSRrzetsmWud3GmK5GNK7j4kNoBQ8l9sz+
AyL1qvRxHTR9G6ljbi6R62l9knPeo3x4fkIR/TKfIjXWuzJiWNBtU8npx4/LUwAA
AIAc0dO5331xiFD6dVYG8BWZoQU9jrabHtkiCJ388SRlebrzyJtjRPfeKgrbzaKi
lCIGES3JR2D//spMw5sF3T5LWcl065aekmBWgqaCPhU8zI9KHPEzBsNe2vKO0w83
NNEhSwdvoIavqkJza+vIh222jdCFOoPKYFUvSrdPD/SxkQ==
Private-MAC: 2b7ffc71d5bc44844be55e17b8475138c114870182b5a9ffd85efb20c007fb4c
""", "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAABFwAAAAdzc2gtcnNhAAAAAwEAAQAAAQEAjQB9ffDcLD+p16QytqgT9S+RLicDyU/t6D8zb7XoIvVr2MdIogsk4d2CqEhCKevYtc5EhmK6bzl6MRW+rJKvVRj9GgAubJoVzVdMzZRpLWVSLofckQGzQvNMvselbU+b+zGcpBhdcebDWIwxx+04HsdobcSlvtlb+Cp74XLaa8FFVP3cJ4Qo2ZFxGrciwxiK9Mr9x9MNQvNLFEr3Ub6GW3U/xNE0Pgl32bJR0w1kV/wRYk/rp8LPG9m3nNq/hDFsMFbIoEP9iBCRycfyr+E7h7syml/Zhx+8LcuQUBnSbzFPUywOYZvwVegiupDg/LV/wm12gS29UwWK/x83Mz7NIwAAA9DQghzb0IIc2wAAAAdzc2gtcnNhAAABAQCNAH198NwsP6nXpDK2qBP1L5EuJwPJT+3oPzNvtegi9WvYx0iiCyTh3YKoSEIp69i1zkSGYrpvOXoxFb6skq9VGP0aAC5smhXNV0zNlGktZVIuh9yRAbNC80y+x6VtT5v7MZykGF1x5sNYjDHH7Tgex2htxKW+2Vv4KnvhctprwUVU/dwnhCjZkXEatyLDGIr0yv3H0w1C80sUSvdRvoZbdT/E0TQ+CXfZslHTDWRX/BFiT+unws8b2bec2r+EMWwwVsigQ/2IEJHJx/Kv4TuHuzKaX9mHH7wty5BQGdJvMU9TLA5hm/BV6CK6kOD8tX/CbXaBLb1TBYr/HzczPs0jAAAAAwEAAQAAAQBxVo6I2D2yV64nBvY4ulvofsa9CWFSxSvfvkWTKu4gg5Of2Xg5wKlx4ILwz7xXRBua7+VG/D8Nvm7I28i7oODMj1yLNNAcPbL+roiuyvfMJKBthIm4CVzVmQn8ZDBF8HLZ9CQMBwW905b8KxysrrKokvMPIL117fOuRD/1lUOazrbTvscMhf93cluGd8yiogaKV++5V1wSg/4PFhissoPtb6fAwW/n8ovJ7p10KewLcop1OJlHIb+RqXwoRWF1H2qftavRUobviIaPsDCDovWNlnRadWcGOWJjuzE8UaNCOPz+O1yvU3rVj1u+++/2iNyvepGpoa0QWaBywcqAYDohAAAAgBzR07nffXGIUPp1VgbwFZmhBT2Otpse2SIInfzxJGV5uvPIm2NE994qCtvNoqKUIgYRLclHYP/+ykzDmwXdPktZyXTrlp6SYFaCpoI+FTzMj0oc8TMGw17a8o7TDzc00SFLB2+ghq+qQnNr68iHbbaN0IU6g8pgVS9Kt08P9LGRAAAAgQDiUXROTvSijFQy/T3EtGIv9CZ1atdVztDPkuxVgvw9TVToqsRN4Y1DNMo42R4nQuRi0/5wI12IpzzvySfqIWFmo3Tu2UacJULyVOd3ztQAvDIW+0T9HMkXhKtJTfUqn/j7DuopifWmV8XqmEDgVb3ALS8XBcv4fzx763WpULWM8QAAAIEAn36TPEaWTInAiQC0HCnsSWYv4cnjEQ0UzMDDPfGE1RmY2SSq8PVO8IZHgm5hAtQMabD/C9qmHlyU0ka83rbJlrndxpiuRjSu4+JDaAUPJfbM/gMi9ar0cR00fRupY24uketpfZJz3qN8eH5CEf0ynyI11rsyYljQbVPJ6cePy1MAAAAOcnNhLXBwazMtcGxhaW4BAgMEBQYHCAkKCwwN", "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCNAH198NwsP6nXpDK2qBP1L5EuJwPJT+3oPzNvtegi9WvYx0iiCyTh3YKoSEIp69i1zkSGYrpvOXoxFb6skq9VGP0aAC5smhXNV0zNlGktZVIuh9yRAbNC80y+x6VtT5v7MZykGF1x5sNYjDHH7Tgex2htxKW+2Vv4KnvhctprwUVU/dwnhCjZkXEatyLDGIr0yv3H0w1C80sUSvdRvoZbdT/E0TQ+CXfZslHTDWRX/BFiT+unws8b2bec2r+EMWwwVsigQ/2IEJHJx/Kv4TuHuzKaX9mHH7wty5BQGdJvMU9TLA5hm/BV6CK6kOD8tX/CbXaBLb1TBYr/HzczPs0j rsa-ppk3-plain"),
        Vec("ed2enc", "test-pass", 2035476582, """PuTTY-User-Key-File-2: ssh-ed25519
Encryption: aes256-cbc
Comment: ed-ppk2
Public-Lines: 2
AAAAC3NzaC1lZDI1NTE5AAAAIBV+o21noW2RlHEAC3OzjI+Nc9aIofhsWgLOkMD4
r6xk
Private-Lines: 1
M/xw47JwGyy6LegPKP2Gvf0l146un/1MGqQTJCQZl+ATT12eSZotNQjIoDmoghAh
Private-MAC: b17e3748514397c7e9eef286e12c3c6c1c0319ee
""", "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZWQyNTUxOQAAACAVfqNtZ6FtkZRxAAtzs4yPjXPWiKH4bFoCzpDA+K+sZAAAAJB5UuhmeVLoZgAAAAtzc2gtZWQyNTUxOQAAACAVfqNtZ6FtkZRxAAtzs4yPjXPWiKH4bFoCzpDA+K+sZAAAAECTYY4MFmNI9swlFumdF8BgCCjfPhEPbg3rJghBr1ZICRV+o21noW2RlHEAC3OzjI+Nc9aIofhsWgLOkMD4r6xkAAAAB2VkLXBwazIBAgMEBQY=", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBV+o21noW2RlHEAC3OzjI+Nc9aIofhsWgLOkMD4r6xk ed-ppk2"),
        Vec("ed2plain", "", 2056237951, """PuTTY-User-Key-File-2: ssh-ed25519
Encryption: none
Comment: ed-ppk2-plain
Public-Lines: 2
AAAAC3NzaC1lZDI1NTE5AAAAIC3fwpIGrULbAi10XkLWUQWyxz8/RqPxIsMLtzZj
MIJx
Private-Lines: 1
AAAAIPHpE+S9rmKyTvSjO1IqNBK7UNovjHVFuwTElRYrlylf
Private-MAC: 5d8a18f544d8d721a73a4d548c3c9ce7cc518e8c
""", "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZWQyNTUxOQAAACAt38KSBq1C2wItdF5C1lEFssc/P0aj8SLDC7c2YzCCcQAAAKB6j7N/eo+zfwAAAAtzc2gtZWQyNTUxOQAAACAt38KSBq1C2wItdF5C1lEFssc/P0aj8SLDC7c2YzCCcQAAAEDx6RPkva5isk70oztSKjQSu1DaL4x1RbsExJUWK5cpXy3fwpIGrULbAi10XkLWUQWyxz8/RqPxIsMLtzZjMIJxAAAADWVkLXBwazItcGxhaW4BAgMEBQYHCAkKCwwNDg8Q", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIC3fwpIGrULbAi10XkLWUQWyxz8/RqPxIsMLtzZjMIJx ed-ppk2-plain"),
    )

    /** Argon2id, посчитанный argon2-cffi (эталонная реализация) для этих файлов. */
    private val argonTable = mapOf(
        "test-pass|0423d4edc7a0967cc14f085453a5605d|1|8192|1" to "73c12bd5332e7638c46a61d092815499c1fe115ea374a97eda362bf05036b3ae6207765850f818a479728d2cb5e5542e2e411906a689258b577f2019e42b841468cde069385c556d8b4fddff81d8ecb4",
        "wrong|0423d4edc7a0967cc14f085453a5605d|1|8192|1" to "885670756cf840bfe78b9c940d155b4159f951cb6a1e91834a80855f70d96283ebbc32d0cb43e0575beda3a819dde1538df8f2c77c10cf8d0958f56811bfaa05b45771322857a4ff7939282ee7e20498",
    )

    private val fakeArgon = PpkConverter.Argon2 { flavor, pw, salt, passes, mem, par, len ->
        assertEquals(PpkConverter.Argon2Flavor.ID, flavor)
        assertEquals(80, len)
        val hex = argonTable["${String(pw, Charsets.UTF_8)}|${salt.joinToString("") { "%02x".format(it) }}|$passes|$mem|$par"]
            ?: error("нет эталона Argon2")
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun body(openssh: String) =
        openssh.lines().filter { it.isNotEmpty() && !it.startsWith("-----") }.joinToString("")

    /**
     * Контейнер без хвостового паддинга приватной секции: puttygen добивает до 16 байт, мак (и мы,
     * как OpenSSH при cipher none) — до 8. Остальное сравнивается байт-в-байт.
     */
    private fun canonical(b64: String): List<Byte> {
        val b = java.util.Base64.getDecoder().decode(b64)
        var i = 15
        fun str(): Int { val l = java.nio.ByteBuffer.wrap(b, i, 4).int; i += 4 + l; return l }
        str(); str(); str(); i += 4; str()
        val innerLen = java.nio.ByteBuffer.wrap(b, i, 4).int
        val start = i + 4
        var inner = b.copyOfRange(start, start + innerLen).toList()
        val k = inner.last().toInt()
        if (k in 1..16 && (1..k).all { inner[inner.size - 1 - (k - it)].toInt() == it }) inner = inner.dropLast(k)
        return b.copyOfRange(0, i).toList() + inner
    }

    private fun innerLen(b64: String): Int {
        val b = java.util.Base64.getDecoder().decode(b64)
        var i = 15
        fun str() { val l = java.nio.ByteBuffer.wrap(b, i, 4).int; i += 4 + l }
        str(); str(); str(); i += 4; str()
        return java.nio.ByteBuffer.wrap(b, i, 4).int
    }

    @Test fun convertsAllVectorsByteForByte() {
        for (v in vectors) {
            val r = PpkConverter.convert(v.ppk, v.pass, fakeArgon, checkInt = v.check)
            assertEquals(v.name, canonical(v.opensshB64), canonical(body(r.openSSH)))
            // приватная секция добита до 8 байт (как мак): длина секции — последний u32 перед ней
            assertEquals(v.name, 0, innerLen(body(r.openSSH)) % 8)
            assertTrue(v.name, innerLen(body(r.openSSH)) < innerLen(v.opensshB64) + 8)
            assertTrue(v.name, r.openSSH.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"))
            assertTrue(v.name, r.openSSH.endsWith("\n-----END OPENSSH PRIVATE KEY-----\n"))
            // строки base64 по 70 символов, как у мака
            val lines = r.openSSH.trimEnd().lines()
            lines.drop(1).dropLast(2).forEach { assertEquals(v.name, 70, it.length) }
            assertTrue(v.name, lines[lines.size - 2].length in 1..70)
        }
    }

    @Test fun publicPartAndFingerprint() {
        for (v in vectors) {
            val r = PpkConverter.convert(v.ppk, v.pass, fakeArgon)
            val pk = KeyTools.publicKey(r.openSSH)
            assertNotNull(v.name, pk)
            assertFalse(v.name, pk!!.isEncrypted)
            val comment = v.pub.substringAfter(' ').substringAfter(' ')
            assertEquals(v.name, v.pub, KeyTools.authorizedKeysLine(pk, comment))
        }
        // ssh-keygen -l для ed3enc
        val pk = KeyTools.publicKey(PpkConverter.convert(vectors[0].ppk, vectors[0].pass, fakeArgon).openSSH)!!
        assertEquals("SHA256:N2QBB741cSzVkRRalpwZxgRA229oaKek0wVb4OlndIA", KeyTools.fingerprint(pk.blob))
        assertEquals("ssh-ed25519", pk.type)
    }

    @Test fun wrongPassphraseIsMacMismatch() {
        for (v in vectors.filter { it.pass.isNotEmpty() }) {
            try {
                PpkConverter.convert(v.ppk, "wrong", fakeArgon)
                fail(v.name)
            } catch (e: PpkConverter.MacMismatch) {
                assertTrue(e.message!!.startsWith("Неверная passphrase (MAC не сошёлся)"))
            }
        }
    }

    @Test fun encryptionDetection() {
        assertTrue(PpkConverter.isEncrypted(vectors[0].ppk))
        assertFalse(PpkConverter.isEncrypted(vectors[2].ppk))
        assertTrue(PpkConverter.isPPK(vectors[0].ppk))
        assertFalse(PpkConverter.isPPK("-----BEGIN OPENSSH PRIVATE KEY-----"))
    }

    /** Результат грузит тот же путь, что и вход по ключу (TerminalController → sshlib PEMDecoder). */
    @Test fun sshlibLoadsConvertedKeys() {
        for (v in vectors) {
            val r = PpkConverter.convert(v.ppk, v.pass, fakeArgon)
            val pair = PEMDecoder.decode(r.openSSH.toCharArray(), null)
            assertNotNull(v.name, pair.private)
            assertNotNull(v.name, pair.public)
        }
    }

    @Test fun crlfPpk() {
        val v = vectors[1]
        val r = PpkConverter.convert(v.ppk.replace("\n", "\r\n"), v.pass, fakeArgon, checkInt = v.check)
        assertEquals(canonical(v.opensshB64), canonical(body(r.openSSH)))
    }

    // ------------------------------------------------------------ проверка текста ключа

    @Test fun rejectsPublicKeys() {
        val pubs = listOf(
            vectors[0].pub,
            "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQC7 user@host",
            "---- BEGIN SSH2 PUBLIC KEY ----\nComment: x\nAAAAC3Nza\n---- END SSH2 PUBLIC KEY ----",
            "-----BEGIN PUBLIC KEY-----\nMIIB\n-----END PUBLIC KEY-----",
            "  \n" + vectors[3].pub + "\n",
        )
        for (p in pubs) {
            val c = KeyImport.check(p)
            assertTrue(p, c is KeyImport.Check.Error)
            assertEquals(KeyImport.PUBLIC_KEY_ERROR, (c as KeyImport.Check.Error).message)
        }
        assertTrue(KeyImport.PUBLIC_KEY_ERROR.startsWith("✗ Это ПУБЛИЧНЫЙ ключ (строка «ssh-ed25519 AAAA…»"))
    }

    @Test fun acceptsPrivateKeys() {
        for (v in vectors) {
            val ossh = PpkConverter.convert(v.ppk, v.pass, fakeArgon).openSSH
            val c = KeyImport.check("\r\n" + ossh.replace("\n", "\r\n"))
            assertTrue(v.name, c is KeyImport.Check.OpenSsh)
            assertEquals(ossh, (c as KeyImport.Check.OpenSsh).text)
            val p = KeyImport.check(v.ppk)
            assertTrue(v.name, p is KeyImport.Check.Ppk)
            assertEquals(v.pass.isNotEmpty(), (p as KeyImport.Check.Ppk).encrypted)
        }
        assertTrue(KeyImport.check("hello") is KeyImport.Check.Error)
        assertEquals(KeyImport.NOT_PRIVATE_ERROR, (KeyImport.check("hello") as KeyImport.Check.Error).message)
        assertTrue(KeyImport.check("-----BEGIN RSA PRIVATE KEY-----\nAAA\n-----END RSA PRIVATE KEY-----") is KeyImport.Check.Error)
        assertTrue(KeyImport.looksLikeKey(vectors[0].ppk))
        assertFalse(KeyImport.looksLikeKey(null))
    }

    @Test fun dedupe() {
        val v = vectors[2]
        val a = PpkConverter.convert(v.ppk, v.pass, fakeArgon, checkInt = 1).openSSH
        val b = PpkConverter.convert(v.ppk, v.pass, fakeArgon, checkInt = 2).openSSH
        val other = PpkConverter.convert(vectors[5].ppk, "", fakeArgon).openSSH
        val keys = listOf(SSHKey(name = "a", privateKey = a), SSHKey(name = "dead", privateKey = other, deleted = true))
        assertEquals("a", KeyImport.findDuplicate(keys, a.trim() + "\n\n")?.name)
        assertEquals("a", KeyImport.findDuplicate(keys, b)?.name)   // тот же ключ, другой checkint
        assertNull(KeyImport.findDuplicate(keys, other))              // tombstone не считается
    }

    @Test fun nameFromFile() {
        assertEquals("work", KeyImport.nameFromFile("work.ppk", vectors[0].ppk))
        assertEquals("id_ed25519", KeyImport.nameFromFile("id_ed25519", "-----BEGIN OPENSSH PRIVATE KEY-----"))
    }
}
