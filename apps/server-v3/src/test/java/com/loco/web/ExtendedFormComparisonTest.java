package com.loco.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.CompatibilityTestBase;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("v2-comparison")
class ExtendedFormComparisonTest extends CompatibilityTestBase {
  @Override
  protected boolean usesV2Http() {
    return false;
  }

  @Test
  void comparesBracketDecodingAgainstV2sActualBodyParser() throws Exception {
    var forms =
        List.of(
            "",
            "content=hello+world",
            "content=%ED%95%9C%EA%B8%80",
            "a=1&a=2",
            "tags[]=a&tags[]=b",
            "a[2]=b&a[0]=c",
            "a[101]=x",
            "a[01]=x",
            "a[b][c]=d",
            "a[b]=c&a=d",
            "a=d&a[b]=c",
            "a[]=x&a[b]=y",
            "a[b]=y&a[]=x",
            "a[][b]=x&a[][c]=y",
            "a[0]=x&a[0]=y",
            "a[__proto__][polluted]=true&b=ok",
            "constructor[prototype][x]=y",
            "a=%FF",
            "a=%GG+z",
            "a=%",
            "a=%E0%A4%A",
            "a.b=x",
            "a",
            "=ignored",
            "&&a=x&&",
            "a[b=x",
            "a[=x",
            "[0]=x",
            "[]=x",
            "a[b]suffix=x",
            "__proto__[x]=y",
            "a[][b]=x&a[][b]=y",
            "a[][]=x&a[][]=y",
            "a[b[c]]=x",
            "a" + "[b]".repeat(32) + "=x",
            "a" + "[b]".repeat(33) + "=x",
            java.util.stream.IntStream.range(0, 1000)
                .mapToObj(i -> "k" + i + "=x")
                .collect(java.util.stream.Collectors.joining("&")),
            java.util.stream.IntStream.range(0, 1001)
                .mapToObj(i -> "k" + i + "=x")
                .collect(java.util.stream.Collectors.joining("&")));
    String script =
        """
        const http=require('node:http'),express=require('express');
        const forms=JSON.parse(process.argv[1]);const app=express();
        app.use(express.urlencoded({limit:'10mb',extended:true}));
        app.post('/',(req,res)=>res.json({body:req.body}));
        app.use((err,req,res,next)=>res.status(err.status||500).json({error:err.message}));
        const server=app.listen(0,'127.0.0.1',async()=>{
          try{const result=[];for(const text of forms){const res=await fetch('http://127.0.0.1:'+server.address().port+'/',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:text});result.push({status:res.status,...await res.json()});}console.log(JSON.stringify(result));}
          finally{server.close();}
        });
        """;
    var observed = environment().nodeCheck(script, forms);
    assertThat(observed.size()).isEqualTo(forms.size());
    for (int i = 0; i < forms.size(); i++) {
      var expected = observed.get(i);
      try {
        var parsed = ExtendedForm.parse(forms.get(i), mapper);
        assertThat(expected.path("status").asInt()).as("form " + i).isEqualTo(200);
        assertThat(parsed)
            .as(
                "form "
                    + i
                    + ": "
                    + forms.get(i).substring(0, Math.min(100, forms.get(i).length())))
            .isEqualTo(expected.path("body"));
      } catch (RequestBodyParser.Failure error) {
        assertThat(error.status).as("form " + i).isEqualTo(expected.path("status").asInt());
        assertThat(error.getMessage()).isEqualTo(expected.path("error").asText());
      }
    }
  }
}
