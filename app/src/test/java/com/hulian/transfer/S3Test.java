package com.hulian.transfer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class S3Test {
    /** AWS 官方文档里的 SigV4 示例（GET ?lifecycle，只签 host / x-amz-content-sha256 / x-amz-date） */
    @Test
    public void 签名_和AWS官方示例一致() {
        String empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String auth = S3.INSTANCE.authorization(
            "GET", "examplebucket.s3.amazonaws.com", "/", "lifecycle=", empty,
            "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1", "20130524T000000Z");
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                + "Signature=fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543",
            auth);
    }

    @Test
    public void Secret_64位十六进制原样用_否则取SHA256() {
        String hex64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertEquals(hex64, S3.INSTANCE.secretOf(hex64));
        // sha256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", S3.INSTANCE.secretOf("abc"));
    }

    @Test
    public void 解析列表_取文件名大小和下一页标记() {
        String xml = "<ListBucketResult><IsTruncated>true</IsTruncated><NextContinuationToken>tok+1</NextContinuationToken>"
            + "<Contents><Key>hulian-relay/dev1/a.bin</Key><LastModified>2026-11-04T07:00:00.000Z</LastModified><Size>12</Size></Contents>"
            + "<Contents><Key>hulian-relay/dev1/b&amp;c.bin</Key><Size>5</Size></Contents></ListBucketResult>";
        S3.Page p = S3.INSTANCE.parseList(xml);
        assertEquals(2, p.getEntries().size());
        assertEquals("a.bin", p.getEntries().get(0).getName());
        assertEquals(12L, p.getEntries().get(0).getSize());
        assertEquals("b&c.bin", p.getEntries().get(1).getName());
        assertEquals("tok+1", p.getNext());
    }
}
