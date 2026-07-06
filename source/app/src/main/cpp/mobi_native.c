#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>

#define TAG "MobiNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define MAX_OUTPUT (3 * 1024 * 1024)

// ---- Huffman tree builder ----
typedef struct { unsigned short code; unsigned char bits; unsigned char value; } HuffEntry;

static int build_huff_table(const unsigned char* data, int data_len, 
                              HuffEntry* table, int max_entries) {
    if (data_len < 24 || memcmp(data, "HUFF", 4) != 0) return 0;
    
    // HUFF record structure at offset 24: cache table + base table
    int table_offset = 24;
    int cache_count = (data[8] << 24) | (data[9] << 16) | (data[10] << 8) | data[11];
    if (cache_count <= 0 || cache_count > 256) cache_count = 256;
    
    // Read cache table (lengths per depth)
    unsigned char lengths[256];
    memcpy(lengths, data + table_offset, cache_count);
    
    // Read base table (base values per depth)
    int base_values[256];
    int base_offset = table_offset + cache_count * 4;
    for (int i = 0; i < 256 && base_offset + 4 <= data_len; i++) {
        base_values[i] = (data[base_offset] << 24) | (data[base_offset+1] << 16) |
                         (data[base_offset+2] << 8) | data[base_offset+3];
        base_offset += 4;
    }
    
    // Build code->value mapping
    int entry = 0;
    unsigned short code = 0;
    for (int depth = 1; depth <= cache_count && depth <= 32 && entry < max_entries; depth++) {
        int count = lengths[depth - 1] & 0xFF;
        int base = (depth - 1 < 256) ? base_values[depth - 1] : 0;
        for (int i = 0; i < count && entry < max_entries; i++) {
            table[entry].code = code;
            table[entry].bits = depth;
            table[entry].value = (unsigned char)(base + i);
            entry++;
            code++;
        }
        code <<= 1;
    }
    return entry;
}

// ---- Bit reader ----
typedef struct {
    const unsigned char* data;
    int data_len;
    int byte_pos;
    int bit_pos;
} BitReader;

static void br_init(BitReader* br, const unsigned char* data, int len) {
    br->data = data; br->data_len = len; br->byte_pos = 0; br->bit_pos = 7;
}

static int br_read_bit(BitReader* br) {
    if (br->byte_pos >= br->data_len) return -1;
    int bit = (br->data[br->byte_pos] >> br->bit_pos) & 1;
    br->bit_pos--;
    if (br->bit_pos < 0) { br->bit_pos = 7; br->byte_pos++; }
    return bit;
}

// ---- Huffman decompress ----
static int huff_decompress(const unsigned char* data, int data_len,
                            HuffEntry* table, int table_entries,
                            char* output, int max_output) {
    BitReader br;
    br_init(&br, data, data_len);
    int out_pos = 0;
    unsigned short code = 0;
    int code_bits = 0;
    
    while (br.byte_pos < br.data_len && out_pos < max_output - 1) {
        int bit = br_read_bit(&br);
        if (bit < 0) break;
        code = (code << 1) | bit;
        code_bits++;
        
        // Look up in table
        for (int i = 0; i < table_entries; i++) {
            if (table[i].bits == code_bits && table[i].code == code) {
                output[out_pos++] = (char)table[i].value;
                code = 0;
                code_bits = 0;
                break;
            }
        }
        
        if (code_bits > 20) { code = 0; code_bits = 0; }
    }
    output[out_pos] = '\0';
    return out_pos;
}

// ---- Main entry point ----
JNIEXPORT jstring JNICALL
Java_com_nous_aurora_data_parser_MobiNative_extractText(
    JNIEnv* env, jclass clazz, jstring jpath) {
    
    const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return NULL;
    
    FILE* fp = fopen(path, "rb");
    if (!fp) { (*env)->ReleaseStringUTFChars(env, jpath, path); return NULL; }
    
    // Read entire file into memory (cap at 15MB)
    fseek(fp, 0, SEEK_END);
    long fsize = ftell(fp);
    if (fsize > 15 * 1024 * 1024) fsize = 15 * 1024 * 1024;
    fseek(fp, 0, SEEK_SET);
    
    unsigned char* fbuf = (unsigned char*)malloc(fsize);
    if (!fbuf) { fclose(fp); (*env)->ReleaseStringUTFChars(env, jpath, path); return NULL; }
    fread(fbuf, 1, fsize, fp);
    fclose(fp);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    
    char* output = (char*)malloc(MAX_OUTPUT);
    if (!output) { free(fbuf); return NULL; }
    memset(output, 0, MAX_OUTPUT);
    
    // Read PalmDB header
    if (fsize < 78) { free(fbuf); free(output); return NULL; }
    int num_records = (fbuf[76] << 8) | fbuf[77];
    LOGI("Records: %d, size: %ld", num_records, fsize);
    if (num_records == 0 || num_records > 50000) { free(fbuf); free(output); return NULL; }
    
    // Read record offsets
    int* offsets = (int*)malloc(num_records * sizeof(int));
    for (int i = 0; i < num_records; i++) {
        int pos = 78 + i * 8;
        if (pos + 4 > fsize) break;
        offsets[i] = (fbuf[pos] << 24) | (fbuf[pos+1] << 16) | (fbuf[pos+2] << 8) | fbuf[pos+3];
    }
    
    // Find the MOBI header (record 0)
    int r0_off = offsets[0];
    if (r0_off < 0 || r0_off >= fsize) { free(offsets); free(fbuf); free(output); return NULL; }
    
    // Find MOBI signature
    int mobi_off = -1;
    for (int i = r0_off; i < fsize - 4 && i < r0_off + 2048; i++) {
        if (fbuf[i] == 'M' && fbuf[i+1] == 'O' && fbuf[i+2] == 'B' && fbuf[i+3] == 'I') {
            mobi_off = i; break;
        }
    }
    
    int enc_type = 0;
    if (mobi_off >= 0 && mobi_off + 4 < fsize) {
        enc_type = fbuf[mobi_off + 3] >> 4;
        LOGI("Enc type: %d", enc_type);
    }
    
    if (enc_type == 4) {
        // Find HUFF record in the file
        int huff_off = -1, cdict_off = -1;
        for (int i = 0; i < fsize - 4; i++) {
            if (fbuf[i] == 'H' && fbuf[i+1] == 'U' && fbuf[i+2] == 'F' && fbuf[i+3] == 'F') {
                huff_off = i; break;
            }
        }
        for (int i = 0; i < fsize - 4; i++) {
            if (fbuf[i] == 'C' && fbuf[i+1] == 'D' && fbuf[i+2] == 'I' && fbuf[i+3] == 'C') {
                cdict_off = i; break;
            }
        }
        
        if (huff_off >= 0) {
            LOGI("HUFF at %d, size ~%d", huff_off, 
                 (cdict_off > huff_off) ? (cdict_off - huff_off) : 4096);
            
            // Build Huffman table from HUFF record
            int huff_size = (cdict_off > huff_off) ? (cdict_off - huff_off) : 
                            (fsize - huff_off < 4096 ? fsize - huff_off : 4096);
            
            HuffEntry* table = (HuffEntry*)malloc(4096 * sizeof(HuffEntry));
            int table_entries = build_huff_table(fbuf + huff_off, huff_size, table, 4096);
            LOGI("Huff table: %d entries", table_entries);
            
            if (table_entries > 0) {
                // Decompress records 2+
                int total = 0;
                for (int i = 2; i < num_records && total < MAX_OUTPUT - 1; i++) {
                    int rec_off = offsets[i];
                    if (rec_off < 0 || rec_off >= fsize) continue;
                    int rec_size = (i < num_records - 1) ? (offsets[i+1] - rec_off) : (fsize - rec_off);
                    if (rec_size <= 0 || rec_size > 500000) continue;
                    
                    int len = huff_decompress(fbuf + rec_off, rec_size, 
                                              table, table_entries,
                                              output + total, MAX_OUTPUT - total);
                    total += len;
                    if (total >= MAX_OUTPUT - 1) break;
                }
                output[total] = '\0';
                LOGI("Decompressed %d bytes total", total);
            }
            
            free(table);
        }
    }
    
    // If nothing decompressed, scan for text
    if (strlen(output) < 50) {
        int out_pos = 0;
        for (long i = 0; i < fsize && out_pos < MAX_OUTPUT - 1; i++) {
            unsigned char c = fbuf[i];
            if ((c >= 0x20 && c <= 0x7E) || c >= 0x80 || c == '\n' || c == '\r' || c == '\t') {
                output[out_pos++] = (char)c;
            } else if (out_pos > 0 && output[out_pos-1] != '\n') {
                output[out_pos++] = '\n';
            }
        }
        output[out_pos] = '\0';
    }
    
    free(offsets);
    free(fbuf);
    
    if (strlen(output) > 20) {
        jstring result = (*env)->NewStringUTF(env, output);
        free(output);
        return result;
    }
    
    free(output);
    return NULL;
}
